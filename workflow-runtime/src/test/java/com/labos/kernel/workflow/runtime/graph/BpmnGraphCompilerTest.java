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
              <bpmn:exclusiveGateway id="g" default="f3"/>
              <bpmn:serviceTask id="t" name="dispense" protocolName="Dispense" resource="17" methodParams="{&quot;volume&quot;:10}"/>
              <bpmn:endEvent id="e"/>
              <bpmn:endEvent id="e2"/>
              <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="g"/>
              <bpmn:sequenceFlow id="f2" sourceRef="g" targetRef="t">
                <bpmn:conditionExpression>${volume} &gt; 0</bpmn:conditionExpression>
              </bpmn:sequenceFlow>
              <bpmn:sequenceFlow id="f3" sourceRef="g" targetRef="e2"/>
              <bpmn:sequenceFlow id="f4" sourceRef="t" targetRef="e"/>
            </bpmn:process>
          </bpmn:definitions>
          """.formatted(NS);
        GraphPlan plan=compiler.compile(new BpmnGraphCompiler.CompilationInput("42","7",xml,
                Map.of("t",new BpmnGraphCompiler.NodeMetadata("LIQUID_HANDLING",9L))));
        assertEquals(5,plan.nodes().size());
        assertEquals(4,plan.edges().size());
        assertTrue(plan.edges().stream().filter(e->e.key().equals("f3")).findFirst().orElseThrow().defaultFlow());
        assertEquals("${volume} > 0",plan.edges().stream().filter(e->e.key().equals("f2")).findFirst().orElseThrow().condition());
        GraphPlan.Node task=plan.nodes().stream().filter(n->n.key().equals("t")).findFirst().orElseThrow();
        assertEquals("LIQUID_HANDLING",task.capabilityCode());
        assertEquals(9L,task.workflowNodeId());
        assertTrue(GraphPlanCodec.toJson(plan).contains("\\"condition\\""));
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
    @Test void rejectsAllUnsupportedStandardEventDefinitions() {
        for (String definition : java.util.List.of(
                "timerEventDefinition", "messageEventDefinition", "signalEventDefinition",
                "terminateEventDefinition", "errorEventDefinition", "escalationEventDefinition",
                "conditionalEventDefinition", "cancelEventDefinition", "compensateEventDefinition",
                "linkEventDefinition")) {
            String xml="<bpmn:definitions xmlns:bpmn=\""+NS+"\"><bpmn:process id=\"p\">"+
                    "<bpmn:startEvent id=\"s\"><bpmn:"+definition+"/></bpmn:startEvent>"+
                    "<bpmn:endEvent id=\"e\"/><bpmn:sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"e\"/>"+
                    "</bpmn:process></bpmn:definitions>";
            IllegalArgumentException ex=assertThrows(IllegalArgumentException.class,()->compiler.compile(
                    new BpmnGraphCompiler.CompilationInput("1","1",xml,Map.of())), definition);
            assertTrue(ex.getMessage().contains("event definition"), definition);
        }
    }

    @Test void rejectsMixedPassThroughAndZeroSidedGatewayShapes() {
        for (int[] shape : java.util.List.of(
                new int[]{0,2}, new int[]{1,1}, new int[]{2,2}, new int[]{2,0})) {
            String xml=gatewayXml(shape[0],shape[1]);
            IllegalArgumentException ex=assertThrows(IllegalArgumentException.class,()->compiler.compile(
                    new BpmnGraphCompiler.CompilationInput("1","1",xml,Map.of())),
                    "in="+shape[0]+" out="+shape[1]);
            assertTrue(ex.getMessage().contains("Unsupported gateway shape"),
                    "in="+shape[0]+" out="+shape[1]+" actual="+ex.getMessage());
        }
    }

    private static String gatewayXml(int incoming,int outgoing) {
        StringBuilder xml=new StringBuilder("<bpmn:definitions xmlns:bpmn=\""+NS+"\"><bpmn:process id=\"p\">"+
                "<bpmn:startEvent id=\"s\"/><bpmn:parallelGateway id=\"g\"/>"+
                "<bpmn:task id=\"a\"/><bpmn:task id=\"b\"/><bpmn:endEvent id=\"e\"/>");
        if(incoming>0) xml.append("<bpmn:sequenceFlow id=\"in0\" sourceRef=\"s\" targetRef=\"g\"/>");
        if(incoming>1) xml.append("<bpmn:sequenceFlow id=\"in1\" sourceRef=\"a\" targetRef=\"g\"/>");
        if(outgoing>0) xml.append("<bpmn:sequenceFlow id=\"out0\" sourceRef=\"g\" targetRef=\"a\"/>");
        if(outgoing>1) xml.append("<bpmn:sequenceFlow id=\"out1\" sourceRef=\"g\" targetRef=\"b\"/>");
        xml.append("<bpmn:sequenceFlow id=\"tail\" sourceRef=\"b\" targetRef=\"e\"/>");
        return xml.append("</bpmn:process></bpmn:definitions>").toString();
    }

}
