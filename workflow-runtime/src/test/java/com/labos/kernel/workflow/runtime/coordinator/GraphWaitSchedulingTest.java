package com.labos.kernel.workflow.runtime.coordinator;

import com.labos.kernel.workflow.runtime.dispatch.LocalTaskDispatchService;
import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit contract tests for AI-3 runtime scheduling: WAIT registration branch guard,
 * runtime validatePlan parity with GraphPlan v2 + §10 parallel/inclusive rules, and
 * ReserveDispatchSummary.hasReadyControlTokens.
 */
class GraphWaitSchedulingTest {

    private static GraphPlan.Node node(String key, GraphPlan.Kind kind, GraphPlan.WaitSpec waitSpec) {
        return new GraphPlan.Node(key, key, kind, "bpmn:" + kind, null, null, null, null, null, null, waitSpec);
    }

    private static GraphPlan.Edge edge(String key, String from, String to) {
        return new GraphPlan.Edge(key, from, to, null, false, null);
    }

    private static GraphPlan.Edge waitEdge(String key, String from, String to, GraphPlan.RouteKind routeKind) {
        return new GraphPlan.Edge(key, from, to, null, false, routeKind);
    }

    private static GraphPlan.WaitSpec messageSpec() {
        return new GraphPlan.WaitSpec(GraphPlan.WaitKind.MESSAGE, "corr-1", null, null);
    }

    private static GraphPlan.WaitSpec messageWithTimeoutSpec() {
        return new GraphPlan.WaitSpec(GraphPlan.WaitKind.MESSAGE_WITH_TIMEOUT, "corr-1", "PT30S", 30_000L);
    }

    private static GraphPlan plan(List<GraphPlan.Node> nodes, List<GraphPlan.Edge> edges) {
        return new GraphPlan(GraphPlan.SCHEMA_VERSION_V2, "test", "def", "1", "sha256:test", nodes, edges);
    }

    @Test
    void transitionForRejectsWaitNodeInsteadOfReturningNull() {
        GraphPlan.Node wait = node("w", GraphPlan.Kind.WAIT, messageSpec());
        List<GraphPlan.Edge> outgoing = List.of(waitEdge("e1", "w", "end", GraphPlan.RouteKind.NORMAL));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> GraphCoordinator.transitionFor(wait, outgoing, Map.of()));
        assertTrue(thrown.getMessage().contains("independent registration branch"));
    }

    @Test
    void validateGraphPlanAcceptsValidMessageAndTimeoutWaitTopologies() {
        GraphPlan msgPlan = plan(
                List.of(node("s", GraphPlan.Kind.START, null),
                        node("w", GraphPlan.Kind.WAIT, messageSpec()),
                        node("end", GraphPlan.Kind.END, null)),
                List.of(edge("e0", "s", "w"), waitEdge("e1", "w", "end", GraphPlan.RouteKind.NORMAL)));
        GraphCoordinator.validateGraphPlan(msgPlan);

        GraphPlan timeoutPlan = plan(
                List.of(node("s", GraphPlan.Kind.START, null),
                        node("w", GraphPlan.Kind.WAIT, messageWithTimeoutSpec()),
                        node("ok", GraphPlan.Kind.END, null),
                        node("late", GraphPlan.Kind.END, null)),
                List.of(edge("e0", "s", "w"),
                        waitEdge("e1", "w", "ok", GraphPlan.RouteKind.NORMAL),
                        waitEdge("e2", "w", "late", GraphPlan.RouteKind.TIMEOUT)));
        GraphCoordinator.validateGraphPlan(timeoutPlan);
    }

    @Test
    void validatePlanIndependentlyEnforcesWaitEdgeParity() {
        GraphPlan valid = plan(
                List.of(node("s", GraphPlan.Kind.START, null),
                        node("w", GraphPlan.Kind.WAIT, messageSpec()),
                        node("end", GraphPlan.Kind.END, null)),
                List.of(edge("e0", "s", "w"), waitEdge("e1", "w", "end", GraphPlan.RouteKind.NORMAL)));
        Map<String, GraphPlan.Node> nodes = Map.of(
                "s", node("s", GraphPlan.Kind.START, null),
                "w", node("w", GraphPlan.Kind.WAIT, messageSpec()),
                "end", node("end", GraphPlan.Kind.END, null),
                "late", node("late", GraphPlan.Kind.END, null));

        // 2 NORMAL edges injected directly into coordinator gate -> fails closed
        IllegalStateException twoNormal = assertThrows(IllegalStateException.class,
                () -> GraphCoordinator.validatePlan(valid, nodes, Map.of(
                        "s", List.of(edge("e0", "s", "w")),
                        "w", List.of(
                                waitEdge("e1", "w", "end", GraphPlan.RouteKind.NORMAL),
                                waitEdge("e1b", "w", "late", GraphPlan.RouteKind.NORMAL)))));
        assertTrue(twoNormal.getMessage().contains("exactly one NORMAL outgoing edge"));

        // TIMEOUT edge injected onto plain MESSAGE Wait -> fails closed
        IllegalStateException badTimeout = assertThrows(IllegalStateException.class,
                () -> GraphCoordinator.validatePlan(valid, nodes, Map.of(
                        "s", List.of(edge("e0", "s", "w")),
                        "w", List.of(
                                waitEdge("e1", "w", "end", GraphPlan.RouteKind.NORMAL),
                                waitEdge("e2", "w", "late", GraphPlan.RouteKind.TIMEOUT)))));
        assertTrue(badTimeout.getMessage().contains("cannot have a TIMEOUT outgoing edge"));
    }

    @Test
    void validateGraphPlanRejectsMessageWithTimeoutInsideParallelOrInclusiveBranch() {
        GraphPlan parallelTimeout = plan(
                List.of(node("s", GraphPlan.Kind.START, null),
                        node("ps", GraphPlan.Kind.SPLIT_PARALLEL, null),
                        node("t", GraphPlan.Kind.TASK, null),
                        node("w", GraphPlan.Kind.WAIT, messageWithTimeoutSpec()),
                        node("pj", GraphPlan.Kind.JOIN_PARALLEL, null),
                        node("e_ok", GraphPlan.Kind.END, null),
                        node("e_to", GraphPlan.Kind.END, null)),
                List.of(edge("f1", "s", "ps"),
                        edge("f2", "ps", "t"),
                        edge("f3", "ps", "w"),
                        edge("f4", "t", "pj"),
                        waitEdge("f5", "w", "pj", GraphPlan.RouteKind.NORMAL),
                        waitEdge("f6", "w", "e_to", GraphPlan.RouteKind.TIMEOUT),
                        edge("f7", "pj", "e_ok")));

        assertThrows(IllegalStateException.class, () -> GraphCoordinator.validateGraphPlan(parallelTimeout));
    }

    @Test
    void reserveDispatchSummaryExposesReadyControlTokensAndKeepsLegacy3ArgCompat() {
        LocalTaskDispatchService.ReserveDispatchSummary withControl =
                new LocalTaskDispatchService.ReserveDispatchSummary(List.of(UUID.randomUUID()), 2, false, true);
        LocalTaskDispatchService.ReserveDispatchSummary legacy3Arg =
                new LocalTaskDispatchService.ReserveDispatchSummary(List.of(), 1, false);

        assertTrue(withControl.hasReadyControlTokens());
        assertEquals(2, withControl.capacityDeferred());
        assertFalse(legacy3Arg.hasReadyControlTokens());
    }
}
