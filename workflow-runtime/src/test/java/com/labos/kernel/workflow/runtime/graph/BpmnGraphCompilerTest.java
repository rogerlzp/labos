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

    @Test void rejectsExecutableElementsOutsideBpmnNamespaceAndForeignSequenceFlows() {
        String wrongNodeNamespace="<bpmn:definitions xmlns:bpmn=\""+NS+"\" xmlns:x=\"urn:example\"><bpmn:process id=\"p\">"+
                "<x:startEvent id=\"s\"/><bpmn:endEvent id=\"e\"/>"+
                "<bpmn:sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"e\"/>"+
                "</bpmn:process></bpmn:definitions>";
        IllegalArgumentException nodeEx=assertThrows(IllegalArgumentException.class,()->compiler.compile(
                new BpmnGraphCompiler.CompilationInput("1","1",wrongNodeNamespace,Map.of())));
        assertTrue(nodeEx.getMessage().contains("Executable BPMN element must use the BPMN namespace"));

        String wrongFlowNamespace="<bpmn:definitions xmlns:bpmn=\""+NS+"\" xmlns:x=\"urn:example\"><bpmn:process id=\"p\">"+
                "<bpmn:startEvent id=\"s\"/><bpmn:endEvent id=\"e\"/>"+
                "<x:sequenceFlow id=\"f\" sourceRef=\"s\" targetRef=\"e\"/>"+
                "</bpmn:process></bpmn:definitions>";
        IllegalArgumentException flowEx=assertThrows(IllegalArgumentException.class,()->compiler.compile(
                new BpmnGraphCompiler.CompilationInput("1","1",wrongFlowNamespace,Map.of())));
        assertTrue(flowEx.getMessage().contains("sequenceFlow must use the BPMN namespace"));
    }

    @Test void rejectsStartEventsWithZeroOrMultipleOutgoingFlows() {
        String zeroOutgoing="<bpmn:definitions xmlns:bpmn=\""+NS+"\"><bpmn:process id=\"p\">"+
                "<bpmn:startEvent id=\"s\"/><bpmn:endEvent id=\"e\"/>"+
                "</bpmn:process></bpmn:definitions>";
        IllegalArgumentException zeroEx=assertThrows(IllegalArgumentException.class,()->compiler.compile(
                new BpmnGraphCompiler.CompilationInput("1","1",zeroOutgoing,Map.of())));
        assertTrue(zeroEx.getMessage().contains("startEvent requires zero incoming and exactly one outgoing"));

        String multipleOutgoing="<bpmn:definitions xmlns:bpmn=\""+NS+"\"><bpmn:process id=\"p\">"+
                "<bpmn:startEvent id=\"s\"/><bpmn:endEvent id=\"e1\"/><bpmn:endEvent id=\"e2\"/>"+
                "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"e1\"/>"+
                "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"s\" targetRef=\"e2\"/>"+
                "</bpmn:process></bpmn:definitions>";
        IllegalArgumentException multipleEx=assertThrows(IllegalArgumentException.class,()->compiler.compile(
                new BpmnGraphCompiler.CompilationInput("1","1",multipleOutgoing,Map.of())));
        assertTrue(multipleEx.getMessage().contains("startEvent requires zero incoming and exactly one outgoing"));
    }

    @Test void rejectsEmptyConditionExpressionInsteadOfCompilingItAsUnconditional() {
        for (String condition : java.util.List.of("", "   ", "\n\t")) {
            String xml="""
              <bpmn:definitions xmlns:bpmn="%s"><bpmn:process id="p">
                <bpmn:startEvent id="s"/><bpmn:exclusiveGateway id="g"/>
                <bpmn:endEvent id="e1"/><bpmn:endEvent id="e2"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="g"/>
                <bpmn:sequenceFlow id="f2" sourceRef="g" targetRef="e1">
                  <bpmn:conditionExpression>%s</bpmn:conditionExpression>
                </bpmn:sequenceFlow>
                <bpmn:sequenceFlow id="f3" sourceRef="g" targetRef="e2"/>
              </bpmn:process></bpmn:definitions>
              """.formatted(NS,condition);
            IllegalArgumentException ex=assertThrows(IllegalArgumentException.class,()->compiler.compile(
                    new BpmnGraphCompiler.CompilationInput("1","1",xml,Map.of())));
            assertTrue(ex.getMessage().contains("conditionExpression must not be empty"),"condition="+condition);
        }
    }

    @Test void rejectsStartEventWithIncomingFlow() {
        String xml="""
          <bpmn:definitions xmlns:bpmn="%s"><bpmn:process id="p">
            <bpmn:startEvent id="s"/><bpmn:endEvent id="e"/>
            <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="e"/>
            <bpmn:sequenceFlow id="f2" sourceRef="e" targetRef="s"/>
          </bpmn:process></bpmn:definitions>
          """.formatted(NS);
        IllegalArgumentException ex=assertThrows(IllegalArgumentException.class,()->compiler.compile(
                new BpmnGraphCompiler.CompilationInput("1","1",xml,Map.of())));
        assertTrue(ex.getMessage().contains("startEvent requires zero incoming and exactly one outgoing"));
        assertTrue(ex.getMessage().contains("in=1 out=1"));
    }

    @Test void compilesCanonicalParallelSplitAndJoinShapes() {
        String xml="<bpmn:definitions xmlns:bpmn=\""+NS+"\"><bpmn:process id=\"p\">"+
                "<bpmn:startEvent id=\"s\"/><bpmn:parallelGateway id=\"split\"/>"+
                "<bpmn:task id=\"a\" protocolName=\"__noop\"/><bpmn:task id=\"b\" protocolName=\"__noop\"/>"+
                "<bpmn:parallelGateway id=\"join\"/><bpmn:endEvent id=\"e\"/>"+
                "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"split\"/>"+
                "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"split\" targetRef=\"a\"/>"+
                "<bpmn:sequenceFlow id=\"f3\" sourceRef=\"split\" targetRef=\"b\"/>"+
                "<bpmn:sequenceFlow id=\"f4\" sourceRef=\"a\" targetRef=\"join\"/>"+
                "<bpmn:sequenceFlow id=\"f5\" sourceRef=\"b\" targetRef=\"join\"/>"+
                "<bpmn:sequenceFlow id=\"f6\" sourceRef=\"join\" targetRef=\"e\"/>"+
                "</bpmn:process></bpmn:definitions>";
        GraphPlan plan=compiler.compile(new BpmnGraphCompiler.CompilationInput("1","1",xml,Map.of()));
        assertEquals(GraphPlan.Kind.SPLIT_PARALLEL,plan.nodes().stream().filter(n->n.key().equals("split")).findFirst().orElseThrow().kind());
        assertEquals(GraphPlan.Kind.JOIN_PARALLEL,plan.nodes().stream().filter(n->n.key().equals("join")).findFirst().orElseThrow().kind());
    }

    @Test void rejectsInvalidGatewayDefaultAndConditionReferences() {
        String danglingDefault="""
          <bpmn:definitions xmlns:bpmn="%s"><bpmn:process id="p">
            <bpmn:startEvent id="s"/><bpmn:exclusiveGateway id="g" default="missing"/>
            <bpmn:endEvent id="e"/>
            <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="g"/>
            <bpmn:sequenceFlow id="f2" sourceRef="g" targetRef="e"/>
          </bpmn:process></bpmn:definitions>
          """.formatted(NS);
        IllegalArgumentException danglingEx=assertThrows(IllegalArgumentException.class,()->compiler.compile(
                new BpmnGraphCompiler.CompilationInput("1","1",danglingDefault,Map.of())));
        assertTrue(danglingEx.getMessage().contains("Gateway default references unknown sequenceFlow"));

        String nonOutgoingDefault="""
          <bpmn:definitions xmlns:bpmn="%s"><bpmn:process id="p">
            <bpmn:startEvent id="s"/><bpmn:exclusiveGateway id="g" default="f2"/>
            <bpmn:endEvent id="e1"/><bpmn:endEvent id="e2"/>
            <bpmn:task id="a" protocolName="__noop"/>
            <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="g"/>
            <bpmn:sequenceFlow id="f3" sourceRef="g" targetRef="e1"/>
            <bpmn:sequenceFlow id="f4" sourceRef="g" targetRef="e2"/>
            <bpmn:sequenceFlow id="f2" sourceRef="a" targetRef="e1"/>
          </bpmn:process></bpmn:definitions>
          """.formatted(NS);
        IllegalArgumentException nonOutgoingEx=assertThrows(IllegalArgumentException.class,()->compiler.compile(
                new BpmnGraphCompiler.CompilationInput("1","1",nonOutgoingDefault,Map.of())));
        assertTrue(nonOutgoingEx.getMessage().contains("Gateway default sequenceFlow is not outgoing"));

        String wrongConditionNamespace="""
          <bpmn:definitions xmlns:bpmn="%s" xmlns:x="urn:example"><bpmn:process id="p">
            <bpmn:startEvent id="s"/><bpmn:endEvent id="e"/>
            <bpmn:sequenceFlow id="f" sourceRef="s" targetRef="e">
              <x:conditionExpression>approved</x:conditionExpression>
            </bpmn:sequenceFlow>
          </bpmn:process></bpmn:definitions>
          """.formatted(NS);
        IllegalArgumentException conditionNsEx=assertThrows(IllegalArgumentException.class,()->compiler.compile(
                new BpmnGraphCompiler.CompilationInput("1","1",wrongConditionNamespace,Map.of())));
        assertTrue(conditionNsEx.getMessage().contains("conditionExpression must use the BPMN namespace"));

        String duplicateCondition="""
          <bpmn:definitions xmlns:bpmn="%s"><bpmn:process id="p">
            <bpmn:startEvent id="s"/><bpmn:endEvent id="e"/>
            <bpmn:sequenceFlow id="f" sourceRef="s" targetRef="e">
              <bpmn:conditionExpression>first</bpmn:conditionExpression>
              <bpmn:conditionExpression>second</bpmn:conditionExpression>
            </bpmn:sequenceFlow>
          </bpmn:process></bpmn:definitions>
          """.formatted(NS);
        IllegalArgumentException duplicateEx=assertThrows(IllegalArgumentException.class,()->compiler.compile(
                new BpmnGraphCompiler.CompilationInput("1","1",duplicateCondition,Map.of())));
        assertTrue(duplicateEx.getMessage().contains("multiple conditionExpression"));
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
