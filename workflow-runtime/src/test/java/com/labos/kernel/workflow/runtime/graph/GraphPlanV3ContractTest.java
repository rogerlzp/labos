package com.labos.kernel.workflow.runtime.graph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract verification for {@link GraphPlan#SCHEMA_VERSION_V3} and {@link GraphPlan.DeadlinePolicy}
 * while preserving V1/V2 compiler & JSON canonical backward compatibility.
 */
class GraphPlanV3ContractTest {

    @Test
    void v3DeadlinePoliciesCompileInsideParallelRegionsAndRoundTripThroughJson() {
        for (GraphPlan.DeadlinePolicy policy : List.of(
                GraphPlan.DeadlinePolicy.ESCALATE_MANUAL,
                GraphPlan.DeadlinePolicy.FAIL_RUN)) {
            String xml = """
                    <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                      xmlns:labos="urn:labos:workflow-runtime:v1">
                      <bpmn:process id="p_v3_%s">
                        <bpmn:startEvent id="start"/>
                        <bpmn:parallelGateway id="split" gatewayDirection="Diverging"/>
                        <bpmn:task id="dispense" protocolName="Dispense" resource="201"/>
                        <bpmn:receiveTask id="wait_human" name="Wait Gate">
                          <bpmn:extensionElements>
                            <labos:wait correlationKey="gate-1" deadlinePolicy="%s"/>
                          </bpmn:extensionElements>
                        </bpmn:receiveTask>
                        <bpmn:boundaryEvent id="timer_wait" attachedToRef="wait_human" cancelActivity="true">
                          <bpmn:timerEventDefinition>
                            <bpmn:timeDuration>PT30M</bpmn:timeDuration>
                          </bpmn:timerEventDefinition>
                        </bpmn:boundaryEvent>
                        <bpmn:parallelGateway id="join" gatewayDirection="Converging"/>
                        <bpmn:endEvent id="end"/>
                        <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="split"/>
                        <bpmn:sequenceFlow id="f2" sourceRef="split" targetRef="dispense"/>
                        <bpmn:sequenceFlow id="f3" sourceRef="split" targetRef="wait_human"/>
                        <bpmn:sequenceFlow id="f4" sourceRef="dispense" targetRef="join"/>
                        <bpmn:sequenceFlow id="f5" sourceRef="wait_human" targetRef="join"/>
                        <bpmn:sequenceFlow id="f6" sourceRef="join" targetRef="end"/>
                      </bpmn:process>
                    </bpmn:definitions>
                    """.formatted(policy.name(), policy.name());

            GraphPlan compiled = new BpmnGraphCompiler().compile(
                    new BpmnGraphCompiler.CompilationInput("v3-" + policy.name(), "1", xml, Map.of()));
            assertEquals(GraphPlan.SCHEMA_VERSION_V3, compiled.schemaVersion());
            GraphPlan.Node waitNode = compiled.nodes().stream()
                    .filter(n -> "wait_human".equals(n.key())).findFirst().orElseThrow();
            assertEquals(GraphPlan.WaitKind.MESSAGE_WITH_TIMEOUT, waitNode.waitSpec().waitKind());
            assertEquals(policy, waitNode.waitSpec().deadlinePolicy());

            String json = GraphPlanCodec.toJson(compiled);
            assertTrue(json.contains("\"deadlinePolicy\":\"" + policy.name() + "\""));
            GraphPlan decoded = GraphPlanCodec.fromJson(json, "test-v3");
            assertEquals(compiled, decoded);
        }
    }

    @Test
    void v2CompatibilityPreservedAndParallelRouteTimeoutEdgeStillRejected() {
        String v2ParallelXml = """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:labos="urn:labos:workflow-runtime:v1">
                  <bpmn:process id="p_v2_parallel">
                    <bpmn:startEvent id="start"/>
                    <bpmn:parallelGateway id="split" gatewayDirection="Diverging"/>
                    <bpmn:task id="dispense" protocolName="Dispense" resource="201"/>
                    <bpmn:receiveTask id="wait_human" name="Wait">
                      <bpmn:extensionElements>
                        <labos:wait correlationKey="gate-1"/>
                      </bpmn:extensionElements>
                    </bpmn:receiveTask>
                    <bpmn:boundaryEvent id="timer_wait" attachedToRef="wait_human" cancelActivity="true">
                      <bpmn:timerEventDefinition>
                        <bpmn:timeDuration>PT5S</bpmn:timeDuration>
                      </bpmn:timerEventDefinition>
                    </bpmn:boundaryEvent>
                    <bpmn:parallelGateway id="join" gatewayDirection="Converging"/>
                    <bpmn:endEvent id="timeout_end"/>
                    <bpmn:endEvent id="end"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="split"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="split" targetRef="wait_human"/>
                    <bpmn:sequenceFlow id="f2b" sourceRef="split" targetRef="dispense"/>
                    <bpmn:sequenceFlow id="f3" sourceRef="wait_human" targetRef="join"/>
                    <bpmn:sequenceFlow id="f3b" sourceRef="dispense" targetRef="join"/>
                    <bpmn:sequenceFlow id="f4" sourceRef="timer_wait" targetRef="timeout_end"/>
                    <bpmn:sequenceFlow id="f5" sourceRef="join" targetRef="end"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new BpmnGraphCompiler().compile(
                        new BpmnGraphCompiler.CompilationInput("v2-par", "1", v2ParallelXml, Map.of())));
        assertTrue(ex.getMessage().contains("MESSAGE_WITH_TIMEOUT Wait is not allowed inside parallel/inclusive gateway region"));

        String serialV2Xml = """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:labos="urn:labos:workflow-runtime:v1">
                  <bpmn:process id="p_v2_serial">
                    <bpmn:startEvent id="start"/>
                    <bpmn:receiveTask id="wait_human" name="Wait">
                      <bpmn:extensionElements>
                        <labos:wait correlationKey="gate-serial"/>
                      </bpmn:extensionElements>
                    </bpmn:receiveTask>
                    <bpmn:boundaryEvent id="timer_wait" attachedToRef="wait_human" cancelActivity="true">
                      <bpmn:timerEventDefinition>
                        <bpmn:timeDuration>PT5S</bpmn:timeDuration>
                      </bpmn:timerEventDefinition>
                    </bpmn:boundaryEvent>
                    <bpmn:endEvent id="timeout_end"/>
                    <bpmn:endEvent id="end"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="wait_human"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="wait_human" targetRef="end"/>
                    <bpmn:sequenceFlow id="f3" sourceRef="timer_wait" targetRef="timeout_end"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        GraphPlan v2 = new BpmnGraphCompiler().compile(
                new BpmnGraphCompiler.CompilationInput("v2-serial", "1", serialV2Xml, Map.of()));
        assertEquals(GraphPlan.SCHEMA_VERSION_V2, v2.schemaVersion());
        String v2Json = GraphPlanCodec.toJson(v2);
        assertTrue(!v2Json.contains("deadlinePolicy"), "V2 canonical JSON must omit deadlinePolicy");
        GraphPlan.Node waitNode = v2.nodes().stream().filter(n -> "wait_human".equals(n.key())).findFirst().orElseThrow();
        assertNull(waitNode.waitSpec().deadlinePolicy());
    }

    @Test
    void invalidV3CombinationsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new GraphPlan.WaitSpec(
                GraphPlan.WaitKind.MESSAGE, "k1", null, null, GraphPlan.DeadlinePolicy.ESCALATE_MANUAL));

        String badTimeoutEdgeXml = """
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:labos="urn:labos:workflow-runtime:v1">
                  <bpmn:process id="p_bad_edge">
                    <bpmn:startEvent id="start"/>
                    <bpmn:receiveTask id="wait_human" name="Wait">
                      <bpmn:extensionElements>
                        <labos:wait correlationKey="gate-1" deadlinePolicy="FAIL_RUN"/>
                      </bpmn:extensionElements>
                    </bpmn:receiveTask>
                    <bpmn:boundaryEvent id="timer_wait" attachedToRef="wait_human" cancelActivity="true">
                      <bpmn:timerEventDefinition>
                        <bpmn:timeDuration>PT5S</bpmn:timeDuration>
                      </bpmn:timerEventDefinition>
                    </bpmn:boundaryEvent>
                    <bpmn:endEvent id="timeout_end"/>
                    <bpmn:endEvent id="end"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="wait_human"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="wait_human" targetRef="end"/>
                    <bpmn:sequenceFlow id="f3" sourceRef="timer_wait" targetRef="timeout_end"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        IllegalArgumentException badEdgeEx = assertThrows(IllegalArgumentException.class, () ->
                new BpmnGraphCompiler().compile(
                        new BpmnGraphCompiler.CompilationInput("bad-v3-edge", "1", badTimeoutEdgeXml, Map.of())));
        assertTrue(badEdgeEx.getMessage().contains("cannot have outgoing sequenceFlow"));

        String tamperedV2Payload = """
                {"schemaVersion":2,"compilerVersion":"1","definitionKey":"k","definitionVersion":"1",
                 "bpmnSha256":"abc","nodes":[
                   {"key":"w","name":"W","kind":"WAIT","sourceType":"receiveTask","protocol":null,
                    "resourceId":null,"methodParams":null,"stageCode":null,"capabilityCode":null,
                    "workflowNodeId":null,
                    "waitSpec":{"waitKind":"MESSAGE_WITH_TIMEOUT","correlationKey":"c","durationIso":"PT1S",
                                "durationMillis":1000,"deadlinePolicy":"ESCALATE_MANUAL"}}
                 ],"edges":[]}
                """;
        assertThrows(IllegalArgumentException.class, () -> GraphPlanCodec.fromJson(tamperedV2Payload, "tampered"));
    }
}
