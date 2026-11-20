package com.labos.kernel.workflow.runtime.coordinator;

import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GraphCoordinatorTest {
    private static GraphPlan.Node node(String key, GraphPlan.Kind kind) {
        return new GraphPlan.Node(key, key, kind, kind.name(), null, null, null, null, null, null);
    }

    private static GraphPlan.Edge edge(String key, String to, String condition, boolean defaultFlow) {
        return new GraphPlan.Edge(key, "gateway", to, condition, defaultFlow);
    }

    private static GraphPlan.Edge edge(String key, String from, String to) {
        return new GraphPlan.Edge(key, from, to, null, false);
    }

    @Test
    void exclusiveSplitSelectsOneMatchingConditionOrDefault() {
        var node = node("gateway", GraphPlan.Kind.SPLIT_XOR);
        var edges = List.of(edge("e1", "a", "amount > 10", false),
                edge("default", "fallback", null, true));
        assertEquals("a", GraphCoordinator.selectExclusive(node, edges, Map.of("amount", 11)).to());
        assertEquals("fallback", GraphCoordinator.selectExclusive(node, edges, Map.of("amount", 10)).to());
    }

    @Test
    void exclusiveSplitRejectsAmbiguousAndMissingRoutes() {
        var node = node("gateway", GraphPlan.Kind.SPLIT_XOR);
        assertThrows(IllegalStateException.class, () -> GraphCoordinator.selectExclusive(node,
                List.of(edge("e1", "a", "flag == true", false), edge("e2", "b", "count > 0", false)),
                Map.of("flag", true, "count", 1)));
        assertThrows(IllegalStateException.class, () -> GraphCoordinator.selectExclusive(node,
                List.of(edge("e1", "a", "flag == true", false)), Map.of("flag", false)));
    }

    @Test
    void inclusiveSplitActivatesEveryMatchingBranchAndOnlyUsesDefaultAsFallback() {
        var node = node("gateway", GraphPlan.Kind.SPLIT_INCLUSIVE);
        var edges = List.of(edge("e1", "a", "left == true", false),
                edge("e2", "b", "right == true", false), edge("default", "fallback", null, true));
        assertEquals(List.of("a", "b"), GraphCoordinator.selectInclusive(node, edges,
                Map.of("left", true, "right", true)).stream().map(GraphPlan.Edge::to).toList());
        assertEquals(List.of("fallback"), GraphCoordinator.selectInclusive(node, edges,
                Map.of("left", false, "right", false)).stream().map(GraphPlan.Edge::to).toList());
        assertEquals(List.of("a"), GraphCoordinator.selectInclusive(node,
                List.of(edge("e1", "a", null, false), edge("default", "fallback", null, true)), Map.of())
                .stream().map(GraphPlan.Edge::to).toList());
        assertThrows(IllegalStateException.class, () -> GraphCoordinator.selectInclusive(node,
                List.of(edge("d1", "fallback-a", null, true), edge("d2", "fallback-b", null, true)), Map.of()));
    }

    @Test
    void inclusiveSplitMaySelectExactlyOneBranchAndJoinRetainsItsNodeIdentity() {
        var inclusive = node("inclusive", GraphPlan.Kind.SPLIT_INCLUSIVE);
        var selected = GraphCoordinator.transitionFor(inclusive,
                List.of(edge("selected", "only", "take == true", false),
                        edge("default", "fallback", null, true)), Map.of("take", true));
        assertEquals("SPLIT", selected.kind());
        assertEquals(List.of("only"), selected.successors());

        var join = node("join", GraphPlan.Kind.JOIN_INCLUSIVE);
        var joinTransition = GraphCoordinator.transitionFor(join,
                List.of(edge("out", "after", null, false)), Map.of());
        assertEquals("JOIN", joinTransition.kind());
        assertEquals("join", joinTransition.joinNodeKey());
        assertEquals(List.of("after"), joinTransition.successors());
    }

    @Test
    void controlNodeKindsMapToExpectedAtomicTransitions() {
        assertEquals("SEQUENCE", GraphCoordinator.transitionFor(node("start", GraphPlan.Kind.START),
                List.of(edge("e", "next", null, false)), Map.of()).kind());
        assertEquals("END", GraphCoordinator.transitionFor(node("end", GraphPlan.Kind.END),
                List.of(), Map.of()).kind());
        assertEquals(List.of("a", "b"), GraphCoordinator.transitionFor(node("parallel", GraphPlan.Kind.SPLIT_PARALLEL),
                List.of(edge("a", "a", null, false), edge("b", "b", null, false)), Map.of()).successors());
        assertEquals("JOIN", GraphCoordinator.transitionFor(node("join", GraphPlan.Kind.JOIN_PARALLEL),
                List.of(edge("e", "next", null, false)), Map.of()).kind());
        assertNull(GraphCoordinator.transitionFor(node("task", GraphPlan.Kind.TASK),
                List.of(edge("e", "next", null, false)), Map.of()));
    }

    @Test
    void validatesUnsupportedTaskRoutingBeforeAnyTokenCanAdvance() {
        var task = node("task", GraphPlan.Kind.TASK);
        var end = node("end", GraphPlan.Kind.END);
        var conditional = new GraphPlan.Edge("task-end", "task", "end", "approved == true", false);
        assertThrows(IllegalStateException.class, () -> GraphCoordinator.validateGraphPlan(
                new GraphPlan(1, "ci", "d", "1", "hash", List.of(task, end), List.of(conditional))));
    }

    @Test
    void rejectsNestedSynchronizingSplitBeforeRunAdvancement() {
        var outer = node("outer", GraphPlan.Kind.SPLIT_PARALLEL);
        var taskA = node("task-a", GraphPlan.Kind.TASK);
        var taskB = node("task-b", GraphPlan.Kind.TASK);
        var inner = node("inner", GraphPlan.Kind.SPLIT_PARALLEL);
        var join = node("join", GraphPlan.Kind.JOIN_PARALLEL);
        var endA = node("end-a", GraphPlan.Kind.END);
        var endB = node("end-b", GraphPlan.Kind.END);
        var nodes = Map.of("outer", outer, "task-a", taskA, "task-b", taskB, "inner", inner,
                "join", join, "end-a", endA, "end-b", endB);
        var edges = List.of(
                edge("outer-a", "outer", "task-a"),
                edge("outer-b", "outer", "task-b"),
                edge("task-inner", "task-a", "inner"),
                edge("task-join", "task-b", "join"),
                edge("inner-a", "inner", "end-a"),
                edge("inner-b", "inner", "end-b"),
                edge("join-out", "join", "end-a"));
        assertThrows(IllegalStateException.class, () -> GraphCoordinator.validateGraphPlan(
                new GraphPlan(1, "ci", "nested", "1", "hash", List.copyOf(nodes.values()), edges)));
    }

    @Test
    void rejectsStructuralMismatchesBeforeRunCreation() {
        // 1. No END node (unbounded cycle).
        assertThrows(IllegalStateException.class, () -> GraphCoordinator.validateGraphPlan(
                new GraphPlan(1, "ci", "no-end", "1", "hash",
                        List.of(node("start", GraphPlan.Kind.START), node("j", GraphPlan.Kind.JOIN_XOR),
                                node("t", GraphPlan.Kind.TASK)),
                        List.of(edge("e1", "start", "j"), edge("e2", "j", "t"), edge("e3", "t", "j")))));

        // 2. XOR split feeding a Parallel Join (unmatched synchronizing Join).
        assertThrows(IllegalStateException.class, () -> GraphCoordinator.validateGraphPlan(
                new GraphPlan(1, "ci", "xor-to-pjoin", "1", "hash",
                        List.of(node("start", GraphPlan.Kind.START), node("xor", GraphPlan.Kind.SPLIT_XOR),
                                node("a", GraphPlan.Kind.TASK), node("b", GraphPlan.Kind.TASK),
                                node("join", GraphPlan.Kind.JOIN_PARALLEL), node("end", GraphPlan.Kind.END)),
                        List.of(edge("e1", "start", "xor"),
                                new GraphPlan.Edge("e2", "xor", "a", "flag == true", false),
                                new GraphPlan.Edge("e3", "xor", "b", null, true),
                                edge("e4", "a", "join"), edge("e5", "b", "join"), edge("e6", "join", "end")))));

        // 3. Parallel Split paired with Inclusive Join (gateway kind mismatch).
        assertThrows(IllegalStateException.class, () -> GraphCoordinator.validateGraphPlan(
                new GraphPlan(1, "ci", "kind-mismatch", "1", "hash",
                        List.of(node("start", GraphPlan.Kind.START), node("split", GraphPlan.Kind.SPLIT_PARALLEL),
                                node("a", GraphPlan.Kind.TASK), node("b", GraphPlan.Kind.TASK),
                                node("join", GraphPlan.Kind.JOIN_INCLUSIVE), node("end", GraphPlan.Kind.END)),
                        List.of(edge("e1", "start", "split"), edge("e2", "split", "a"), edge("e3", "split", "b"),
                                edge("e4", "a", "join"), edge("e5", "b", "join"), edge("e6", "join", "end")))));

        // 4. Same Split mixing End and Join.
        assertThrows(IllegalStateException.class, () -> GraphCoordinator.validateGraphPlan(
                new GraphPlan(1, "ci", "mixed-end-join", "1", "hash",
                        List.of(node("start", GraphPlan.Kind.START), node("split", GraphPlan.Kind.SPLIT_PARALLEL),
                                node("a", GraphPlan.Kind.TASK), node("b", GraphPlan.Kind.TASK),
                                node("join", GraphPlan.Kind.JOIN_PARALLEL), node("end", GraphPlan.Kind.END)),
                        List.of(edge("e1", "start", "split"), edge("e2", "split", "a"), edge("e3", "split", "b"),
                                edge("e4", "a", "end"), edge("e5", "b", "join"), edge("e6", "join", "end")))));

        // 5. External path bypassing owning Split into synchronizing Join.
        assertThrows(IllegalStateException.class, () -> GraphCoordinator.validateGraphPlan(
                new GraphPlan(1, "ci", "bypass-join", "1", "hash",
                        List.of(node("start", GraphPlan.Kind.START), node("xor", GraphPlan.Kind.SPLIT_XOR),
                                node("split", GraphPlan.Kind.SPLIT_PARALLEL), node("a", GraphPlan.Kind.TASK),
                                node("b", GraphPlan.Kind.TASK), node("bypass", GraphPlan.Kind.TASK),
                                node("join", GraphPlan.Kind.JOIN_PARALLEL), node("end", GraphPlan.Kind.END)),
                        List.of(edge("e0", "start", "xor"),
                                new GraphPlan.Edge("e1", "xor", "split", "useSplit == true", false),
                                new GraphPlan.Edge("e2", "xor", "bypass", null, true),
                                edge("e3", "split", "a"), edge("e4", "split", "b"),
                                edge("e5", "a", "join"), edge("e6", "b", "join"),
                                edge("e7", "bypass", "join"), edge("e8", "join", "end")))));

        // 6. Statically non-boolean condition expression.
        assertThrows(IllegalArgumentException.class, () -> GraphCoordinator.validateGraphPlan(
                new GraphPlan(1, "ci", "non-bool", "1", "hash",
                        List.of(node("start", GraphPlan.Kind.START), node("xor", GraphPlan.Kind.SPLIT_XOR),
                                node("e1", GraphPlan.Kind.END), node("e2", GraphPlan.Kind.END)),
                        List.of(edge("s", "start", "xor"),
                                new GraphPlan.Edge("c1", "xor", "e1", "123", false),
                                new GraphPlan.Edge("c2", "xor", "e2", null, true)))));
    }

    @Test
    void parallelSplitRejectsConditionalEdgesAndDuplicateTargets() {
        var node = node("parallel", GraphPlan.Kind.SPLIT_PARALLEL);
        assertThrows(IllegalStateException.class, () -> GraphCoordinator.transitionFor(node,
                List.of(edge("a", "a", "flag == true", false), edge("b", "b", null, false)), Map.of("flag", true)));
        assertThrows(IllegalStateException.class, () -> GraphCoordinator.transitionFor(node,
                List.of(edge("a", "same", null, false), edge("b", "same", null, false)), Map.of()));
    }
}
