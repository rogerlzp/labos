package com.labos.kernel.workflow.runtime.coordinator;

import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GraphCoordinatorTest {
    private static GraphPlan.Node node(String key, GraphPlan.Kind kind) {
        return new GraphPlan.Node(key,key,kind,kind.name(),null,null,null,null,null,null);
    }

    private static GraphPlan.Edge edge(String key,String to,String condition,boolean defaultFlow) {
        return new GraphPlan.Edge(key,"gateway",to,condition,defaultFlow);
    }

    @Test
    void exclusiveSplitSelectsOneMatchingConditionOrDefault() {
        var node=node("gateway",GraphPlan.Kind.SPLIT_XOR);
        var edges=List.of(edge("e1","a","amount > 10",false),
                edge("default","fallback",null,true));
        assertEquals("a",GraphCoordinator.selectExclusive(node,edges,Map.of("amount",11)).to());
        assertEquals("fallback",GraphCoordinator.selectExclusive(node,edges,Map.of("amount",10)).to());
    }

    @Test
    void exclusiveSplitRejectsAmbiguousAndMissingRoutes() {
        var node=node("gateway",GraphPlan.Kind.SPLIT_XOR);
        assertThrows(IllegalStateException.class,()->GraphCoordinator.selectExclusive(node,
                List.of(edge("e1","a","flag == true",false),edge("e2","b","count > 0",false)),
                Map.of("flag",true,"count",1)));
        assertThrows(IllegalStateException.class,()->GraphCoordinator.selectExclusive(node,
                List.of(edge("e1","a","flag == true",false)),Map.of("flag",false)));
    }

    @Test
    void inclusiveSplitActivatesEveryMatchingBranchAndOnlyUsesDefaultAsFallback() {
        var node=node("gateway",GraphPlan.Kind.SPLIT_INCLUSIVE);
        var edges=List.of(edge("e1","a","left == true",false),
                edge("e2","b","right == true",false),edge("default","fallback",null,true));
        assertEquals(List.of("a","b"),GraphCoordinator.selectInclusive(node,edges,
                Map.of("left",true,"right",true)).stream().map(GraphPlan.Edge::to).toList());
        assertEquals(List.of("fallback"),GraphCoordinator.selectInclusive(node,edges,
                Map.of("left",false,"right",false)).stream().map(GraphPlan.Edge::to).toList());
        assertEquals(List.of("a"),GraphCoordinator.selectInclusive(node,
                List.of(edge("e1","a",null,false),edge("default","fallback",null,true)),Map.of())
                .stream().map(GraphPlan.Edge::to).toList());
        assertThrows(IllegalStateException.class,()->GraphCoordinator.selectInclusive(node,
                List.of(edge("d1","fallback-a",null,true),edge("d2","fallback-b",null,true)),Map.of()));
    }

    @Test
    void inclusiveSplitMaySelectExactlyOneBranchAndJoinRetainsItsNodeIdentity() {
        var inclusive=node("inclusive",GraphPlan.Kind.SPLIT_INCLUSIVE);
        var selected=GraphCoordinator.transitionFor(inclusive,
                List.of(edge("selected","only","take == true",false),
                        edge("default","fallback",null,true)),Map.of("take",true));
        assertEquals("SPLIT",selected.kind());
        assertEquals(List.of("only"),selected.successors());

        var join=node("join",GraphPlan.Kind.JOIN_INCLUSIVE);
        var joinTransition=GraphCoordinator.transitionFor(join,
                List.of(edge("out","after",null,false)),Map.of());
        assertEquals("JOIN",joinTransition.kind());
        assertEquals("join",joinTransition.joinNodeKey());
        assertEquals(List.of("after"),joinTransition.successors());
    }

    @Test
    void controlNodeKindsMapToExpectedAtomicTransitions() {
        assertEquals("SEQUENCE",GraphCoordinator.transitionFor(node("start",GraphPlan.Kind.START),
                List.of(edge("e","next",null,false)),Map.of()).kind());
        assertEquals("END",GraphCoordinator.transitionFor(node("end",GraphPlan.Kind.END),
                List.of(),Map.of()).kind());
        assertEquals(List.of("a","b"),GraphCoordinator.transitionFor(node("parallel",GraphPlan.Kind.SPLIT_PARALLEL),
                List.of(edge("a","a",null,false),edge("b","b",null,false)),Map.of()).successors());
        assertEquals("JOIN",GraphCoordinator.transitionFor(node("join",GraphPlan.Kind.JOIN_PARALLEL),
                List.of(edge("e","next",null,false)),Map.of()).kind());
        assertNull(GraphCoordinator.transitionFor(node("task",GraphPlan.Kind.TASK),
                List.of(edge("e","next",null,false)),Map.of()));
    }

    @Test
    void validatesUnsupportedTaskRoutingBeforeAnyTokenCanAdvance() {
        var task=node("task",GraphPlan.Kind.TASK);
        var end=node("end",GraphPlan.Kind.END);
        var conditional=new GraphPlan.Edge("task-end","task","end","approved == true",false);
        var nodes=Map.of("task",task,"end",end);
        assertThrows(IllegalStateException.class,()->GraphCoordinator.validatePlan(
                new GraphPlan(1,"ci","d","1","hash",List.of(task,end),List.of(conditional)),
                nodes,Map.of("task",List.of(conditional))));
    }

    @Test
    void parallelSplitRejectsConditionalEdgesAndDuplicateTargets() {
        var node=node("parallel",GraphPlan.Kind.SPLIT_PARALLEL);
        assertThrows(IllegalStateException.class,()->GraphCoordinator.transitionFor(node,
                List.of(edge("a","a","flag == true",false),edge("b","b",null,false)),Map.of("flag",true)));
        assertThrows(IllegalStateException.class,()->GraphCoordinator.transitionFor(node,
                List.of(edge("a","same",null,false),edge("b","same",null,false)),Map.of()));
    }
}
