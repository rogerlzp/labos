package com.labos.kernel.workflow.runtime.graph;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BpmnGraphCompilerTest {
    private static final String NS="http://www.omg.org/spec/BPMN/20100524/MODEL";
    private final BpmnGraphCompiler compiler=new BpmnGraphCompiler();

    @Test void compilesTaskGatewaysConditionsAndDefaultFlow() {
        String xml="""
          <bpmn:definitions xmlns:bpmn="%s">
            <bpmn:process id="p">
              <bpmn:startEvent id="s"/>
              <bpmn:exclusiveGateway id="g" default="f4"/>
              <bpmn:serviceTask id="t" name="dispense" protocolName="Dispense" resource="17" methodParams="{&quot;volume&quot;:10}"/>
              <bpmn:endEvent id="e"/>
              <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="g"/>
              <bpmn:sequenceFlow id="f2" sourceRef="g" targetRef="t"/>
              <bpmn:sequenceFlow id="f3" sourceRef="t" targetRef="e"/>
            </bpmn:process>
          </bpmn:definitions>
          """.formatted(NS);
        GraphPlan plan=compiler.compile(new BpmnGraphCompiler.CompilationInput("42","7",xml,
                Map.of("t",new BpmnGraphCompiler.NodeMetadata("LIQUID_HANDLING",9L))));
        assertEquals(4,plan.nodes().size());
        assertEquals(3,plan.edges().size());
        assertTrue(plan.edges().stream().filter(e->e.key().equals("f4")).findFirst().orElseThrow().defaultFlow());
        GraphPlan.Node task=plan.nodes().stream().filter(n->n.key().equals("t")).findFirst().orElseThrow();
        assertEquals("LIQUID_HANDLING",task.capabilityCode());
        assertEquals(9L,task.workflowNodeId());
        assertTrue(GraphPlanCodec.toJson(plan).contains("\"condition\""));
        assertEquals(GraphPlanCodec.sha256(xml),plan.bpmnSha256());
    }

    @Test void graphPlanSnapshotRoundTripsWithoutChangingItsHash() {
        String xml="<bpmn:definitions xmlns:bpmn=\""+NS+"\"><bpmn:process id=\"p\"><bpmn:startEvent id=\"s\"/><bpmn:endEvent id=\"e\"/><bpmn:sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"e\"/></bpmn:process></bpmn:definitions>";
        GraphPlan plan=compiler.compile(new BpmnGraphCompiler.CompilationInput("1","v1",xml,Map.of()));
        GraphPlan decoded=GraphPlanCodec.fromJson(GraphPlanCodec.toJson(plan));
        assertEquals(plan,decoded);
        assertDoesNotThrow(()->GraphPlanCodec.verifyBpmnHash(decoded,plan.bpmnSha256()));
        assertThrows(IllegalArgumentException.class,()->GraphPlanCodec.verifyBpmnHash(decoded,"00"));
    }

    @Test void rejectsUnsupportedExecutableElementsInsteadOfDroppingThem() {
        String xml="<bpmn:definitions xmlns:bpmn=\""+NS+"\"><bpmn:process id=\"p\"><bpmn:startEvent id=\"s\"/><bpmn:subProcess id=\"sub\"/></bpmn:process></bpmn:definitions>";
        IllegalArgumentException ex=assertThrows(IllegalArgumentException.class,()->compiler.compile(
                new BpmnGraphCompiler.CompilationInput("1","1",xml,Map.of())));
        assertTrue(ex.getMessage().contains("Unsupported BPMN element"));
    }

    @Test void rejectsDanglingEdgesAndDuplicateIds() {
        String xml="<bpmn:definitions xmlns:bpmn=\""+NS+"\"><bpmn:process id=\"p\"><bpmn:startEvent id=\"s\"/><bpmn:endEvent id=\"e\"/><bpmn:sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"missing\"/></bpmn:process></bpmn:definitions>";
        assertThrows(IllegalArgumentException.class,()->compiler.compile(
                new BpmnGraphCompiler.CompilationInput("1","1",xml,Map.of())));
    }

    @Test void rejectsEventDefinitionsInsteadOfSilentlyTreatingThemAsPlainEvents() {
        String xml="<bpmn:definitions xmlns:bpmn=\""+NS+"\"><bpmn:process id=\"p\">"+
                "<bpmn:startEvent id=\"s\"><bpmn:timerEventDefinition/></bpmn:startEvent>"+
                "<bpmn:endEvent id=\"e\"/><bpmn:sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"e\"/>"+
                "</bpmn:process></bpmn:definitions>";
        IllegalArgumentException ex=assertThrows(IllegalArgumentException.class,()->compiler.compile(
                new BpmnGraphCompiler.CompilationInput("1","1",xml,Map.of())));
        assertTrue(ex.getMessage().contains("event definition"));
    }

    @Test void rejectsAmbiguousMixedGatewayAndInvalidDefaultFlow() {
        String mixed="<bpmn:definitions xmlns:bpmn=\""+NS+"\"><bpmn:process id=\"p\">"+
                "<bpmn:startEvent id=\"s\"/><bpmn:parallelGateway id=\"g\"/>"+
                "<bpmn:task id=\"a\"/><bpmn:task id=\"b\"/><bpmn:endEvent id=\"e\"/>"+
                "<bpmn:sequenceFlow id=\"f0\" sourceRef=\"s\" targetRef=\"g\"/>"+
                "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"a\" targetRef=\"g\"/>"+
                "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"g\" targetRef=\"a\"/>"+
                "<bpmn:sequenceFlow id=\"f3\" sourceRef=\"g\" targetRef=\"b\"/>"+
                "<bpmn:sequenceFlow id=\"f4\" sourceRef=\"a\" targetRef=\"e\"/>"+
                "<bpmn:sequenceFlow id=\"f5\" sourceRef=\"b\" targetRef=\"e\"/>"+
                "</bpmn:process></bpmn:definitions>";
        assertThrows(IllegalArgumentException.class,()->compiler.compile(
                new BpmnGraphCompiler.CompilationInput("1","1",mixed,Map.of())));

        String badDefault="<bpmn:definitions xmlns:bpmn=\""+NS+"\"><bpmn:process id=\"p\">"+
                "<bpmn:startEvent id=\"s\"/><bpmn:exclusiveGateway id=\"g\" default=\"f1\"/>"+
                "<bpmn:task id=\"a\"/><bpmn:endEvent id=\"e\"/>"+
                "<bpmn:sequenceFlow id=\"f0\" sourceRef=\"s\" targetRef=\"g\"/>"+
                "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"a\" targetRef=\"g\"/>"+
                "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"g\" targetRef=\"a\"/>"+
                "<bpmn:sequenceFlow id=\"f3\" sourceRef=\"g\" targetRef=\"e\"/>"+
                "</bpmn:process></bpmn:definitions>";
        assertThrows(IllegalArgumentException.class,()->compiler.compile(
                new BpmnGraphCompiler.CompilationInput("1","1",badDefault,Map.of())));
    }
}
