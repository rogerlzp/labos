package com.labos.kernel.workflow.runtime.coordinator;

import com.labos.kernel.workflow.runtime.graph.BpmnGraphCompiler;
import com.labos.kernel.workflow.runtime.graph.DeterministicConditionEvaluator;
import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class GraphCoordinatorTest {
    private final BpmnGraphCompiler compiler = new BpmnGraphCompiler();
    private final GraphCoordinator coordinator = new GraphCoordinator(new DeterministicConditionEvaluator());
    private static final String NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    private GraphPlan compile(String body) {
        String xml = "<bpmn:definitions xmlns:bpmn=\"" + NS + "\"><bpmn:process id=\"p\">" +
                body + "</bpmn:process></bpmn:definitions>";
        return compiler.compile(new BpmnGraphCompiler.CompilationInput("test","1",xml,Map.of()));
    }

    @Test void sequentialTaskAdvancesAndEndCompletes() {
        GraphPlan plan = compile("<bpmn:startEvent id=\"s\"/><bpmn:task id=\"a\"/><bpmn:endEvent id=\"e\"/>"+
                "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"a\"/>"+
                "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"a\" targetRef=\"e\"/>");
        var next = coordinator.onActivationSucceeded(plan,"a",Map.of(),null);
        assertEquals(GraphCoordinator.DecisionKind.ADVANCE,next.kind());
        assertEquals(List.of("e"),next.nextNodeKeys());
        assertEquals(GraphCoordinator.DecisionKind.COMPLETE,
                coordinator.onActivationSucceeded(plan,"e",Map.of(),null).kind());
    }

    @Test void xorSelectsExactlyOneMatchOrDefault() {
        GraphPlan plan = compile("<bpmn:startEvent id=\"s\"/><bpmn:exclusiveGateway id=\"g\" default=\"f3\"/>"+
                "<bpmn:task id=\"a\"/><bpmn:task id=\"b\"/><bpmn:task id=\"c\"/>"+
                "<bpmn:sequenceFlow id=\"f0\" sourceRef=\"s\" targetRef=\"g\"/>"+
                "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"g\" targetRef=\"a\"><bpmn:conditionExpression>x == 1</bpmn:conditionExpression></bpmn:sequenceFlow>"+
                "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"g\" targetRef=\"b\"><bpmn:conditionExpression>x == 2</bpmn:conditionExpression></bpmn:sequenceFlow>"+
                "<bpmn:sequenceFlow id=\"f3\" sourceRef=\"g\" targetRef=\"c\"/></bpmn:process>");
        assertEquals(List.of("b"),coordinator.onActivationSucceeded(plan,"g",Map.of("x",2),null).nextNodeKeys());
        assertEquals(List.of("c"),coordinator.onActivationSucceeded(plan,"g",Map.of("x",9),null).nextNodeKeys());
        assertThrows(IllegalStateException.class,()->coordinator.onActivationSucceeded(plan,"g",Map.of("x",1),null));
    }

    @Test void parallelAndInclusiveSplitsProduceSelectedBranchSets() {
        GraphPlan parallel = compile("<bpmn:startEvent id=\"s\"/><bpmn:parallelGateway id=\"g\"/>"+
                "<bpmn:task id=\"a\"/><bpmn:task id=\"b\"/>"+
                "<bpmn:sequenceFlow id=\"f0\" sourceRef=\"s\" targetRef=\"g\"/>"+
                "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"g\" targetRef=\"a\"/>"+
                "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"g\" targetRef=\"b\"/></bpmn:process>");
        assertEquals(Set.of("a","b"),Set.copyOf(coordinator.onActivationSucceeded(parallel,"g",Map.of(),null).nextNodeKeys()));

        GraphPlan inclusive = compile("<bpmn:startEvent id=\"s\"/><bpmn:inclusiveGateway id=\"g\" default=\"f3\"/>"+
                "<bpmn:task id=\"a\"/><bpmn:task id=\"b\"/><bpmn:task id=\"c\"/>"+
                "<bpmn:sequenceFlow id=\"f0\" sourceRef=\"s\" targetRef=\"g\"/>"+
                "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"g\" targetRef=\"a\"><bpmn:conditionExpression>a == true</bpmn:conditionExpression></bpmn:sequenceFlow>"+
                "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"g\" targetRef=\"b\"><bpmn:conditionExpression>b == true</bpmn:conditionExpression></bpmn:sequenceFlow>"+
                "<bpmn:sequenceFlow id=\"f3\" sourceRef=\"g\" targetRef=\"c\"/></bpmn:process>");
        assertEquals(Set.of("a","b"),Set.copyOf(coordinator.onActivationSucceeded(inclusive,"g",Map.of("a",true,"b",true),null).nextNodeKeys()));
    }

    @Test void parallelJoinWaitsForEveryExpectedBranch() {
        GraphPlan plan = compile("<bpmn:startEvent id=\"s\"/><bpmn:task id=\"a\"/><bpmn:task id=\"b\"/>"+
                "<bpmn:parallelGateway id=\"j\"/><bpmn:endEvent id=\"e\"/>"+
                "<bpmn:sequenceFlow id=\"f0\" sourceRef=\"s\" targetRef=\"a\"/>"+
                "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"b\"/>"+
                "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"a\" targetRef=\"j\"/>"+
                "<bpmn:sequenceFlow id=\"f3\" sourceRef=\"b\" targetRef=\"j\"/>"+
                "<bpmn:sequenceFlow id=\"f4\" sourceRef=\"j\" targetRef=\"e\"/>");
        var waiting = coordinator.onActivationSucceeded(plan,"j",Map.of(),new GraphCoordinator.JoinProgress(Set.of("a","b"),Set.of("a")));
        assertEquals(GraphCoordinator.DecisionKind.WAIT_FOR_JOIN,waiting.kind());
        var released = coordinator.onActivationSucceeded(plan,"j",Map.of(),new GraphCoordinator.JoinProgress(Set.of("a","b"),Set.of("a","b")));
        assertEquals(GraphCoordinator.DecisionKind.JOIN_RELEASED,released.kind());
        assertEquals(List.of("e"),released.nextNodeKeys());
    }
}
