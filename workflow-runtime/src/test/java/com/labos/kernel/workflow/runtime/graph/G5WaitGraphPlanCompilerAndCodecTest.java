package com.labos.kernel.workflow.runtime.graph;

import com.labos.kernel.workflow.runtime.graph.BpmnGraphCompiler.CompilationInput;
import com.labos.kernel.workflow.runtime.graph.BpmnGraphCompiler.NodeMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class G5WaitGraphPlanCompilerAndCodecTest {

    private final BpmnGraphCompiler compiler = new BpmnGraphCompiler();

    private static final String NS_HEADER = """
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              xmlns:labos="urn:labos:workflow-runtime:v1"
                              targetNamespace="http://labos/test">
            """;

    private GraphPlan compile(String body, Map<String, NodeMetadata> metadata) {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + NS_HEADER
                + "  <bpmn:process id=\"p\" isExecutable=\"true\">\n"
                + body
                + "  </bpmn:process>\n</bpmn:definitions>\n";
        return compiler.compile(new CompilationInput("wf-g5", "1", xml, metadata));
    }

    private GraphPlan compile(String body) {
        return compile(body, Map.of());
    }

    private static GraphPlan.Node node(GraphPlan plan, String key) {
        return plan.nodes().stream().filter(n -> key.equals(n.key())).findFirst().orElseThrow();
    }

    private static GraphPlan.Edge edge(GraphPlan plan, String key) {
        return plan.edges().stream().filter(e -> key.equals(e.key())).findFirst().orElseThrow();
    }

    // -------------------------------------------------------------------------
    // §5.1 receiveTask Wait vs legacy compatibility
    // -------------------------------------------------------------------------

    @Test
    void compilesExplicitReceiveTaskWaitToV2AndAllowsStageAndWorkflowNodeId() {
        GraphPlan plan = compile("""
                <bpmn:startEvent id="s"/>
                <bpmn:receiveTask id="w1" name="Await Approval" stageCode="PREPARE">
                  <bpmn:extensionElements>
                    <labos:wait correlationKey="sample-123"/>
                  </bpmn:extensionElements>
                </bpmn:receiveTask>
                <bpmn:endEvent id="e"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                <bpmn:sequenceFlow id="f2" sourceRef="w1" targetRef="e"/>
                """, Map.of("w1", new NodeMetadata(null, 42L)));

        assertEquals(GraphPlan.SCHEMA_VERSION_V2, plan.schemaVersion());
        GraphPlan.Node w1 = node(plan, "w1");
        assertEquals(GraphPlan.Kind.WAIT, w1.kind());
        assertEquals("PREPARE", w1.stageCode());
        assertEquals(42L, w1.workflowNodeId());
        assertNull(w1.protocol());
        assertNull(w1.resourceId());
        assertNull(w1.methodParams());
        assertNull(w1.capabilityCode());
        assertNotNull(w1.waitSpec());
        assertEquals(GraphPlan.WaitKind.MESSAGE, w1.waitSpec().waitKind());
        assertEquals("sample-123", w1.waitSpec().correlationKey());
        assertNull(w1.waitSpec().durationIso());
        assertNull(w1.waitSpec().durationMillis());
        assertEquals(GraphPlan.RouteKind.NORMAL, edge(plan, "f2").routeKind());
        assertNull(edge(plan, "f1").routeKind());
    }

    @Test
    void preservesLegacyReceiveTaskAsV1TaskAndOmitsV2JsonFields() {
        GraphPlan plan = compile("""
                <bpmn:startEvent id="s"/>
                <bpmn:receiveTask id="r1" name="Device Receive" protocolName="OPCUA" resource="1001" methodParams="{}"/>
                <bpmn:endEvent id="e"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="r1"/>
                <bpmn:sequenceFlow id="f2" sourceRef="r1" targetRef="e"/>
                """);

        assertEquals(GraphPlan.SCHEMA_VERSION_V1, plan.schemaVersion());
        GraphPlan.Node r1 = node(plan, "r1");
        assertEquals(GraphPlan.Kind.TASK, r1.kind());
        assertNull(r1.waitSpec());
        assertNull(edge(plan, "f2").routeKind());

        String v1Json = GraphPlanCodec.toJson(plan);
        assertFalse(v1Json.contains("waitSpec"), v1Json);
        assertFalse(v1Json.contains("routeKind"), v1Json);
        assertEquals(plan, GraphPlanCodec.fromJson(v1Json));
    }

    @Test
    void rejectsReceiveTaskMixingWaitExtensionWithAnyTaskConfig() {
        assertThrows(IllegalArgumentException.class, () -> compile("""
                <bpmn:startEvent id="s"/>
                <bpmn:receiveTask id="w1" protocolName="HTTP">
                  <bpmn:extensionElements><labos:wait correlationKey="k1"/></bpmn:extensionElements>
                </bpmn:receiveTask>
                <bpmn:endEvent id="e"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                <bpmn:sequenceFlow id="f2" sourceRef="w1" targetRef="e"/>
                """));
        assertThrows(IllegalArgumentException.class, () -> compile("""
                <bpmn:startEvent id="s"/>
                <bpmn:receiveTask id="w1" resource="99">
                  <bpmn:extensionElements><labos:wait correlationKey="k1"/></bpmn:extensionElements>
                </bpmn:receiveTask>
                <bpmn:endEvent id="e"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                <bpmn:sequenceFlow id="f2" sourceRef="w1" targetRef="e"/>
                """));
        assertThrows(IllegalArgumentException.class, () -> compile("""
                <bpmn:startEvent id="s"/>
                <bpmn:receiveTask id="w1" methodParams="{}">
                  <bpmn:extensionElements><labos:wait correlationKey="k1"/></bpmn:extensionElements>
                </bpmn:receiveTask>
                <bpmn:endEvent id="e"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                <bpmn:sequenceFlow id="f2" sourceRef="w1" targetRef="e"/>
                """));
        assertThrows(IllegalArgumentException.class, () -> compile("""
                <bpmn:startEvent id="s"/>
                <bpmn:receiveTask id="w1">
                  <bpmn:extensionElements><labos:wait correlationKey="k1"/></bpmn:extensionElements>
                </bpmn:receiveTask>
                <bpmn:endEvent id="e"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                <bpmn:sequenceFlow id="f2" sourceRef="w1" targetRef="e"/>
                """, Map.of("w1", new NodeMetadata("CAP_READ", 7L))));
    }

    @Test
    void rejectsDynamicBlankOrMisplacedLabosWaitExtension() {
        // Dynamic expression ${...}
        assertThrows(IllegalArgumentException.class, () -> compile("""
                <bpmn:startEvent id="s"/>
                <bpmn:receiveTask id="w1">
                  <bpmn:extensionElements><labos:wait correlationKey="order-${runId}"/></bpmn:extensionElements>
                </bpmn:receiveTask>
                <bpmn:endEvent id="e"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                <bpmn:sequenceFlow id="f2" sourceRef="w1" targetRef="e"/>
                """));
        // Blank correlationKey
        assertThrows(IllegalArgumentException.class, () -> compile("""
                <bpmn:startEvent id="s"/>
                <bpmn:receiveTask id="w1">
                  <bpmn:extensionElements><labos:wait correlationKey="   "/></bpmn:extensionElements>
                </bpmn:receiveTask>
                <bpmn:endEvent id="e"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                <bpmn:sequenceFlow id="f2" sourceRef="w1" targetRef="e"/>
                """));
        // Bare receiveTask with neither task config nor wait extension
        assertThrows(IllegalArgumentException.class, () -> compile("""
                <bpmn:startEvent id="s"/>
                <bpmn:receiveTask id="w1"/>
                <bpmn:endEvent id="e"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                <bpmn:sequenceFlow id="f2" sourceRef="w1" targetRef="e"/>
                """));
        // Placed on serviceTask instead of receiveTask
        assertThrows(IllegalArgumentException.class, () -> compile("""
                <bpmn:startEvent id="s"/>
                <bpmn:serviceTask id="t1" protocolName="HTTP" resource="1">
                  <bpmn:extensionElements><labos:wait correlationKey="k1"/></bpmn:extensionElements>
                </bpmn:serviceTask>
                <bpmn:endEvent id="e"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="t1"/>
                <bpmn:sequenceFlow id="f2" sourceRef="t1" targetRef="e"/>
                """));
    }

    // -------------------------------------------------------------------------
    // §5.2 Timer Catch Event
    // -------------------------------------------------------------------------

    @Test
    void compilesTimerIntermediateCatchEventWithStrictDurationBounds() {
        GraphPlan plan = compile("""
                <bpmn:startEvent id="s"/>
                <bpmn:intermediateCatchEvent id="t1" name="Hold">
                  <bpmn:timerEventDefinition>
                    <bpmn:timeDuration xsi:type="bpmn:tFormalExpression">PT1.5S</bpmn:timeDuration>
                  </bpmn:timerEventDefinition>
                </bpmn:intermediateCatchEvent>
                <bpmn:endEvent id="e"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="t1"/>
                <bpmn:sequenceFlow id="f2" sourceRef="t1" targetRef="e"/>
                """);

        assertEquals(GraphPlan.SCHEMA_VERSION_V2, plan.schemaVersion());
        GraphPlan.Node t1 = node(plan, "t1");
        assertEquals(GraphPlan.Kind.WAIT, t1.kind());
        assertEquals(GraphPlan.WaitKind.TIMER, t1.waitSpec().waitKind());
        assertNull(t1.waitSpec().correlationKey());
        assertEquals("PT1.5S", t1.waitSpec().durationIso());
        assertEquals(1500L, t1.waitSpec().durationMillis());
        assertEquals(GraphPlan.RouteKind.NORMAL, edge(plan, "f2").routeKind());

        // Exact 365 days max boundary succeeds.
        GraphPlan maxPlan = compile("""
                <bpmn:startEvent id="s"/>
                <bpmn:intermediateCatchEvent id="t1">
                  <bpmn:timerEventDefinition>
                    <bpmn:timeDuration>P365D</bpmn:timeDuration>
                  </bpmn:timerEventDefinition>
                </bpmn:intermediateCatchEvent>
                <bpmn:endEvent id="e"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="t1"/>
                <bpmn:sequenceFlow id="f2" sourceRef="t1" targetRef="e"/>
                """);
        assertEquals(GraphPlan.MAX_WAIT_DURATION_MILLIS, node(maxPlan, "t1").waitSpec().durationMillis());
    }

    @Test
    void rejectsUnsupportedTimerDefinitionsAndInvalidDurations() {
        for (String defBody : new String[]{
                "<bpmn:timeDate>2026-12-01T00:00:00Z</bpmn:timeDate>",
                "<bpmn:timeCycle>R3/PT10S</bpmn:timeCycle>",
                "<bpmn:timeDuration>PT0S</bpmn:timeDuration>",
                "<bpmn:timeDuration>-PT5S</bpmn:timeDuration>",
                "<bpmn:timeDuration>PT0.0001S</bpmn:timeDuration>",
                "<bpmn:timeDuration>P366D</bpmn:timeDuration>",
                "<bpmn:timeDuration>P1M</bpmn:timeDuration>",
                "<bpmn:timeDuration>P1Y</bpmn:timeDuration>",
                "<bpmn:timeDuration>PT99999999999999999S</bpmn:timeDuration>",
                "<bpmn:timeDuration>${delay}</bpmn:timeDuration>",
                "<bpmn:timeDuration>   </bpmn:timeDuration>",
                ""
        }) {
            String timerXml = defBody.isEmpty()
                    ? "<bpmn:intermediateCatchEvent id=\"t1\"/>"
                    : """
                      <bpmn:intermediateCatchEvent id="t1">
                        <bpmn:timerEventDefinition>%s</bpmn:timerEventDefinition>
                      </bpmn:intermediateCatchEvent>
                      """.formatted(defBody);
            assertThrows(IllegalArgumentException.class, () -> compile("""
                    <bpmn:startEvent id="s"/>
                    %s
                    <bpmn:endEvent id="e"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="t1"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="t1" targetRef="e"/>
                    """.formatted(timerXml)), "Should reject: " + defBody);
        }
    }

    // -------------------------------------------------------------------------
    // §5.3 Interrupting Timeout Boundary Event
    // -------------------------------------------------------------------------

    @Test
    void normalizesInterruptingTimeoutBoundaryEventWithoutCreatingBoundaryNode() {
        GraphPlan plan = compile("""
                <bpmn:startEvent id="s"/>
                <bpmn:receiveTask id="w1" name="Wait Assay">
                  <bpmn:extensionElements>
                    <labos:wait correlationKey="assay-9"/>
                  </bpmn:extensionElements>
                </bpmn:receiveTask>
                <bpmn:boundaryEvent id="b1" attachedToRef="w1" cancelActivity="true">
                  <bpmn:timerEventDefinition>
                    <bpmn:timeDuration>PT45S</bpmn:timeDuration>
                  </bpmn:timerEventDefinition>
                </bpmn:boundaryEvent>
                <bpmn:endEvent id="e_ok"/>
                <bpmn:endEvent id="e_to"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                <bpmn:sequenceFlow id="f_norm" sourceRef="w1" targetRef="e_ok"/>
                <bpmn:sequenceFlow id="f_to" sourceRef="b1" targetRef="e_to"/>
                """);

        assertEquals(GraphPlan.SCHEMA_VERSION_V2, plan.schemaVersion());
        // No independent boundaryEvent node exists.
        assertEquals(4, plan.nodes().size());
        assertTrue(plan.nodes().stream().noneMatch(n -> "b1".equals(n.key())));

        GraphPlan.Node w1 = node(plan, "w1");
        assertEquals(GraphPlan.Kind.WAIT, w1.kind());
        assertEquals(GraphPlan.WaitKind.MESSAGE_WITH_TIMEOUT, w1.waitSpec().waitKind());
        assertEquals("assay-9", w1.waitSpec().correlationKey());
        assertEquals("PT45S", w1.waitSpec().durationIso());
        assertEquals(45_000L, w1.waitSpec().durationMillis());

        GraphPlan.Edge normal = edge(plan, "f_norm");
        assertEquals("w1", normal.from());
        assertEquals("e_ok", normal.to());
        assertEquals(GraphPlan.RouteKind.NORMAL, normal.routeKind());

        GraphPlan.Edge timeout = edge(plan, "f_to");
        assertEquals("w1", timeout.from());
        assertEquals("e_to", timeout.to());
        assertEquals(GraphPlan.RouteKind.TIMEOUT, timeout.routeKind());
    }

    @Test
    void rejectsInvalidBoundaryEventShapes() {
        // Non-interrupting cancelActivity="false"
        assertThrows(IllegalArgumentException.class, () -> compile("""
                <bpmn:startEvent id="s"/>
                <bpmn:receiveTask id="w1">
                  <bpmn:extensionElements><labos:wait correlationKey="k1"/></bpmn:extensionElements>
                </bpmn:receiveTask>
                <bpmn:boundaryEvent id="b1" attachedToRef="w1" cancelActivity="false">
                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT10S</bpmn:timeDuration></bpmn:timerEventDefinition>
                </bpmn:boundaryEvent>
                <bpmn:endEvent id="e1"/><bpmn:endEvent id="e2"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                <bpmn:sequenceFlow id="f2" sourceRef="w1" targetRef="e1"/>
                <bpmn:sequenceFlow id="f3" sourceRef="b1" targetRef="e2"/>
                """));

        // Attached to serviceTask (not a Wait receiveTask)
        assertThrows(IllegalArgumentException.class, () -> compile("""
                <bpmn:startEvent id="s"/>
                <bpmn:serviceTask id="t1" protocolName="HTTP" resource="1"/>
                <bpmn:boundaryEvent id="b1" attachedToRef="t1" cancelActivity="true">
                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT10S</bpmn:timeDuration></bpmn:timerEventDefinition>
                </bpmn:boundaryEvent>
                <bpmn:endEvent id="e1"/><bpmn:endEvent id="e2"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="t1"/>
                <bpmn:sequenceFlow id="f2" sourceRef="t1" targetRef="e1"/>
                <bpmn:sequenceFlow id="f3" sourceRef="b1" targetRef="e2"/>
                """));

        // Attached to legacy receiveTask
        assertThrows(IllegalArgumentException.class, () -> compile("""
                <bpmn:startEvent id="s"/>
                <bpmn:receiveTask id="r1" protocolName="OPCUA" resource="1"/>
                <bpmn:boundaryEvent id="b1" attachedToRef="r1" cancelActivity="true">
                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT10S</bpmn:timeDuration></bpmn:timerEventDefinition>
                </bpmn:boundaryEvent>
                <bpmn:endEvent id="e1"/><bpmn:endEvent id="e2"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="r1"/>
                <bpmn:sequenceFlow id="f2" sourceRef="r1" targetRef="e1"/>
                <bpmn:sequenceFlow id="f3" sourceRef="b1" targetRef="e2"/>
                """));

        // Multiple boundaryEvents on same Wait
        assertThrows(IllegalArgumentException.class, () -> compile("""
                <bpmn:startEvent id="s"/>
                <bpmn:receiveTask id="w1">
                  <bpmn:extensionElements><labos:wait correlationKey="k1"/></bpmn:extensionElements>
                </bpmn:receiveTask>
                <bpmn:boundaryEvent id="b1" attachedToRef="w1" cancelActivity="true">
                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT10S</bpmn:timeDuration></bpmn:timerEventDefinition>
                </bpmn:boundaryEvent>
                <bpmn:boundaryEvent id="b2" attachedToRef="w1" cancelActivity="true">
                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT20S</bpmn:timeDuration></bpmn:timerEventDefinition>
                </bpmn:boundaryEvent>
                <bpmn:endEvent id="e1"/><bpmn:endEvent id="e2"/><bpmn:endEvent id="e3"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                <bpmn:sequenceFlow id="f2" sourceRef="w1" targetRef="e1"/>
                <bpmn:sequenceFlow id="f3" sourceRef="b1" targetRef="e2"/>
                <bpmn:sequenceFlow id="f4" sourceRef="b2" targetRef="e3"/>
                """));

        // Same target node for NORMAL and TIMEOUT
        assertThrows(IllegalArgumentException.class, () -> compile("""
                <bpmn:startEvent id="s"/>
                <bpmn:receiveTask id="w1">
                  <bpmn:extensionElements><labos:wait correlationKey="k1"/></bpmn:extensionElements>
                </bpmn:receiveTask>
                <bpmn:boundaryEvent id="b1" attachedToRef="w1" cancelActivity="true">
                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT10S</bpmn:timeDuration></bpmn:timerEventDefinition>
                </bpmn:boundaryEvent>
                <bpmn:endEvent id="e_same"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                <bpmn:sequenceFlow id="f2" sourceRef="w1" targetRef="e_same"/>
                <bpmn:sequenceFlow id="f3" sourceRef="b1" targetRef="e_same"/>
                """));

        // Boundary ID collides with node ID
        assertThrows(IllegalArgumentException.class, () -> compile("""
                <bpmn:startEvent id="s"/>
                <bpmn:receiveTask id="w1">
                  <bpmn:extensionElements><labos:wait correlationKey="k1"/></bpmn:extensionElements>
                </bpmn:receiveTask>
                <bpmn:boundaryEvent id="e_ok" attachedToRef="w1" cancelActivity="true">
                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT10S</bpmn:timeDuration></bpmn:timerEventDefinition>
                </bpmn:boundaryEvent>
                <bpmn:endEvent id="e_ok"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                <bpmn:sequenceFlow id="f2" sourceRef="w1" targetRef="e_ok"/>
                """));
    }

    // -------------------------------------------------------------------------
    // §10 Parallel / Inclusive Gateway Post-Dominator Rules
    // -------------------------------------------------------------------------

    @Test
    void rejectsTimeoutWaitInsideParallelAndInclusiveSplitRegions() {
        // Escaping parallel join -> rejected
        assertThrows(IllegalArgumentException.class, () -> compile("""
                <bpmn:startEvent id="s"/>
                <bpmn:parallelGateway id="ps"/>
                <bpmn:serviceTask id="t_other" protocolName="HTTP" resource="1"/>
                <bpmn:receiveTask id="w1">
                  <bpmn:extensionElements><labos:wait correlationKey="k1"/></bpmn:extensionElements>
                </bpmn:receiveTask>
                <bpmn:boundaryEvent id="b1" attachedToRef="w1" cancelActivity="true">
                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT10S</bpmn:timeDuration></bpmn:timerEventDefinition>
                </bpmn:boundaryEvent>
                <bpmn:parallelGateway id="pj"/>
                <bpmn:endEvent id="e_ok"/>
                <bpmn:endEvent id="e_to"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="ps"/>
                <bpmn:sequenceFlow id="f2" sourceRef="ps" targetRef="t_other"/>
                <bpmn:sequenceFlow id="f3" sourceRef="ps" targetRef="w1"/>
                <bpmn:sequenceFlow id="f4" sourceRef="t_other" targetRef="pj"/>
                <bpmn:sequenceFlow id="f5" sourceRef="w1" targetRef="pj"/>
                <bpmn:sequenceFlow id="f6" sourceRef="b1" targetRef="e_to"/>
                <bpmn:sequenceFlow id="f7" sourceRef="pj" targetRef="e_ok"/>
                """));

        // Converging back into parallel join -> still inside parallel region, so rejected
        assertThrows(IllegalArgumentException.class, () -> compile("""
                <bpmn:startEvent id="s"/>
                <bpmn:parallelGateway id="ps"/>
                <bpmn:serviceTask id="t_other" protocolName="HTTP" resource="1"/>
                <bpmn:receiveTask id="w1">
                  <bpmn:extensionElements><labos:wait correlationKey="k1"/></bpmn:extensionElements>
                </bpmn:receiveTask>
                <bpmn:boundaryEvent id="b1" attachedToRef="w1" cancelActivity="true">
                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT10S</bpmn:timeDuration></bpmn:timerEventDefinition>
                </bpmn:boundaryEvent>
                <bpmn:serviceTask id="t_fallback" protocolName="HTTP" resource="2"/>
                <bpmn:parallelGateway id="pj"/>
                <bpmn:endEvent id="e_ok"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="ps"/>
                <bpmn:sequenceFlow id="f2" sourceRef="ps" targetRef="t_other"/>
                <bpmn:sequenceFlow id="f3" sourceRef="ps" targetRef="w1"/>
                <bpmn:sequenceFlow id="f4" sourceRef="t_other" targetRef="pj"/>
                <bpmn:sequenceFlow id="f5" sourceRef="w1" targetRef="pj"/>
                <bpmn:sequenceFlow id="f6" sourceRef="b1" targetRef="t_fallback"/>
                <bpmn:sequenceFlow id="f7" sourceRef="t_fallback" targetRef="pj"/>
                <bpmn:sequenceFlow id="f8" sourceRef="pj" targetRef="e_ok"/>
                """));

        // Inside inclusive split region -> rejected
        assertThrows(IllegalArgumentException.class, () -> compile("""
                <bpmn:startEvent id="s"/>
                <bpmn:inclusiveGateway id="is"/>
                <bpmn:serviceTask id="t_other" protocolName="HTTP" resource="1"/>
                <bpmn:receiveTask id="w1">
                  <bpmn:extensionElements><labos:wait correlationKey="k1"/></bpmn:extensionElements>
                </bpmn:receiveTask>
                <bpmn:boundaryEvent id="b1" attachedToRef="w1" cancelActivity="true">
                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT10S</bpmn:timeDuration></bpmn:timerEventDefinition>
                </bpmn:boundaryEvent>
                <bpmn:inclusiveGateway id="ij"/>
                <bpmn:endEvent id="e_ok"/>
                <bpmn:endEvent id="e_to"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="is"/>
                <bpmn:sequenceFlow id="f2" sourceRef="is" targetRef="t_other">
                  <bpmn:conditionExpression>flag == true</bpmn:conditionExpression>
                </bpmn:sequenceFlow>
                <bpmn:sequenceFlow id="f3" sourceRef="is" targetRef="w1">
                  <bpmn:conditionExpression>flag == false</bpmn:conditionExpression>
                </bpmn:sequenceFlow>
                <bpmn:sequenceFlow id="f4" sourceRef="t_other" targetRef="ij"/>
                <bpmn:sequenceFlow id="f5" sourceRef="w1" targetRef="ij"/>
                <bpmn:sequenceFlow id="f6" sourceRef="b1" targetRef="e_to"/>
                <bpmn:sequenceFlow id="f7" sourceRef="ij" targetRef="e_ok"/>
                """));
    }

    @Test
    void allowsPlainWaitOrTimerInParallelAndAllowsTimeoutWaitAfterJoinOrInXor() {
        // Plain MESSAGE Wait and TIMER Wait inside parallel branches -> allowed!
        GraphPlan parallelPlain = compile("""
                <bpmn:startEvent id="s"/>
                <bpmn:parallelGateway id="ps"/>
                <bpmn:receiveTask id="w_msg">
                  <bpmn:extensionElements><labos:wait correlationKey="k_msg"/></bpmn:extensionElements>
                </bpmn:receiveTask>
                <bpmn:intermediateCatchEvent id="w_tmr">
                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT5S</bpmn:timeDuration></bpmn:timerEventDefinition>
                </bpmn:intermediateCatchEvent>
                <bpmn:parallelGateway id="pj"/>
                <bpmn:receiveTask id="w_after_join">
                  <bpmn:extensionElements><labos:wait correlationKey="k_after"/></bpmn:extensionElements>
                </bpmn:receiveTask>
                <bpmn:boundaryEvent id="b_after" attachedToRef="w_after_join" cancelActivity="true">
                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT10S</bpmn:timeDuration></bpmn:timerEventDefinition>
                </bpmn:boundaryEvent>
                <bpmn:endEvent id="e_ok"/>
                <bpmn:endEvent id="e_to"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="ps"/>
                <bpmn:sequenceFlow id="f2" sourceRef="ps" targetRef="w_msg"/>
                <bpmn:sequenceFlow id="f3" sourceRef="ps" targetRef="w_tmr"/>
                <bpmn:sequenceFlow id="f4" sourceRef="w_msg" targetRef="pj"/>
                <bpmn:sequenceFlow id="f5" sourceRef="w_tmr" targetRef="pj"/>
                <bpmn:sequenceFlow id="f6" sourceRef="pj" targetRef="w_after_join"/>
                <bpmn:sequenceFlow id="f7" sourceRef="w_after_join" targetRef="e_ok"/>
                <bpmn:sequenceFlow id="f8" sourceRef="b_after" targetRef="e_to"/>
                """);
        assertEquals(GraphPlan.WaitKind.MESSAGE, node(parallelPlain, "w_msg").waitSpec().waitKind());
        assertEquals(GraphPlan.WaitKind.TIMER, node(parallelPlain, "w_tmr").waitSpec().waitKind());
        assertEquals(GraphPlan.WaitKind.MESSAGE_WITH_TIMEOUT, node(parallelPlain, "w_after_join").waitSpec().waitKind());

        // MESSAGE_WITH_TIMEOUT inside XOR branch -> allowed!
        GraphPlan xorTimeout = compile("""
                <bpmn:startEvent id="s"/>
                <bpmn:exclusiveGateway id="xs"/>
                <bpmn:receiveTask id="w1">
                  <bpmn:extensionElements><labos:wait correlationKey="k_xor"/></bpmn:extensionElements>
                </bpmn:receiveTask>
                <bpmn:boundaryEvent id="b1" attachedToRef="w1" cancelActivity="true">
                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT10S</bpmn:timeDuration></bpmn:timerEventDefinition>
                </bpmn:boundaryEvent>
                <bpmn:serviceTask id="t2" protocolName="HTTP" resource="1"/>
                <bpmn:endEvent id="e1"/>
                <bpmn:endEvent id="e2"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="xs"/>
                <bpmn:sequenceFlow id="f2" sourceRef="xs" targetRef="w1">
                  <bpmn:conditionExpression>flag == true</bpmn:conditionExpression>
                </bpmn:sequenceFlow>
                <bpmn:sequenceFlow id="f3" sourceRef="xs" targetRef="t2">
                  <bpmn:conditionExpression>flag == false</bpmn:conditionExpression>
                </bpmn:sequenceFlow>
                <bpmn:sequenceFlow id="f4" sourceRef="w1" targetRef="e1"/>
                <bpmn:sequenceFlow id="f5" sourceRef="b1" targetRef="e2"/>
                <bpmn:sequenceFlow id="f6" sourceRef="t2" targetRef="e1"/>
                """);
        assertEquals(GraphPlan.WaitKind.MESSAGE_WITH_TIMEOUT, node(xorTimeout, "w1").waitSpec().waitKind());
    }

    // -------------------------------------------------------------------------
    // H2 GraphPlan & GraphPlanCodec v1/v2 Validation & Compatibility
    // -------------------------------------------------------------------------

    @Test
    void codecRoundTripsV2WaitPlanAndRejectsMalformedOrMismatchedPayloads() {
        GraphPlan v2 = compile("""
                <bpmn:startEvent id="s"/>
                <bpmn:receiveTask id="w1">
                  <bpmn:extensionElements><labos:wait correlationKey="corr-1"/></bpmn:extensionElements>
                </bpmn:receiveTask>
                <bpmn:boundaryEvent id="b1" attachedToRef="w1" cancelActivity="true">
                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT20S</bpmn:timeDuration></bpmn:timerEventDefinition>
                </bpmn:boundaryEvent>
                <bpmn:intermediateCatchEvent id="t1">
                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT5S</bpmn:timeDuration></bpmn:timerEventDefinition>
                </bpmn:intermediateCatchEvent>
                <bpmn:endEvent id="e_ok"/>
                <bpmn:endEvent id="e_to"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                <bpmn:sequenceFlow id="f2" sourceRef="w1" targetRef="t1"/>
                <bpmn:sequenceFlow id="f3" sourceRef="b1" targetRef="e_to"/>
                <bpmn:sequenceFlow id="f4" sourceRef="t1" targetRef="e_ok"/>
                """);

        String v2Json = GraphPlanCodec.toJson(v2);
        assertTrue(v2Json.contains("\"schemaVersion\":2"));
        assertTrue(v2Json.contains("\"waitSpec\":"));
        assertTrue(v2Json.contains("\"routeKind\":\"TIMEOUT\""));
        assertEquals(v2, GraphPlanCodec.fromJson(v2Json));

        // schemaVersion=1 JSON claiming WAIT / waitSpec / routeKind -> rejected!
        String v1WithWaitNode = v2Json.replace("\"schemaVersion\":2", "\"schemaVersion\":1");
        assertThrows(IllegalArgumentException.class, () -> GraphPlanCodec.fromJson(v1WithWaitNode, "ctx"));

        String v1WithRouteKind = """
                {"schemaVersion":1,"compilerVersion":"graph-compiler-v1","definitionKey":"k","definitionVersion":"1","bpmnSha256":"abc",
                 "nodes":[{"key":"s","name":"s","kind":"START","sourceType":"startEvent","protocol":null,"resourceId":null,"methodParams":null,"stageCode":null,"capabilityCode":null,"workflowNodeId":null},
                          {"key":"e","name":"e","kind":"END","sourceType":"endEvent","protocol":null,"resourceId":null,"methodParams":null,"stageCode":null,"capabilityCode":null,"workflowNodeId":null}],
                 "edges":[{"key":"f1","from":"s","to":"e","condition":null,"defaultFlow":false,"routeKind":"NORMAL"}]}
                """;
        assertThrows(IllegalArgumentException.class, () -> GraphPlanCodec.fromJson(v1WithRouteKind));

        // Unsupported schemaVersion=3 -> rejected with diagnostic message
        String v3 = v2Json.replace("\"schemaVersion\":2", "\"schemaVersion\":3");
        IllegalArgumentException v3Ex = assertThrows(
                IllegalArgumentException.class, () -> GraphPlanCodec.fromJson(v3, "run=1"));
        assertTrue(v3Ex.getMessage().contains("Unsupported GraphPlan schemaVersion: 3"));

        // Mismatched durationMillis in JSON -> rejected
        String badMillis = v2Json.replace("\"durationMillis\":20000", "\"durationMillis\":19999");
        assertThrows(IllegalArgumentException.class, () -> GraphPlanCodec.fromJson(badMillis));

        // WAIT node missing waitSpec -> rejected
        assertThrows(IllegalArgumentException.class, () -> new GraphPlan.Node(
                "w1", "w1", GraphPlan.Kind.WAIT, "receiveTask", null, null, null, null, null, null, null));

        // Non-WAIT node carrying waitSpec -> rejected
        assertThrows(IllegalArgumentException.class, () -> new GraphPlan.Node(
                "t1", "t1", GraphPlan.Kind.TASK, "serviceTask", "HTTP", 1L, null, null, null, null,
                new GraphPlan.WaitSpec(GraphPlan.WaitKind.MESSAGE, "k1", null, null)));
    }

    @Test
    void rejectsBoundaryEventWithoutExplicitCancelActivityTrue() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> compile("""
                <bpmn:startEvent id="s"/>
                <bpmn:receiveTask id="w1">
                  <bpmn:extensionElements><labos:wait correlationKey="k1"/></bpmn:extensionElements>
                </bpmn:receiveTask>
                <bpmn:boundaryEvent id="b1" attachedToRef="w1">
                  <bpmn:timerEventDefinition><bpmn:timeDuration>PT10S</bpmn:timeDuration></bpmn:timerEventDefinition>
                </bpmn:boundaryEvent>
                <bpmn:endEvent id="e1"/><bpmn:endEvent id="e2"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                <bpmn:sequenceFlow id="f2" sourceRef="w1" targetRef="e1"/>
                <bpmn:sequenceFlow id="f3" sourceRef="b1" targetRef="e2"/>
                """));
        assertTrue(ex.getMessage().contains("cancelActivity"), ex.getMessage());
    }

    @Test
    void rejectsUnknownExtensionElementsWhilePreservingBpmnDiAndDocumentation() {
        // Unknown LabOS extension element inside receiveTask extensionElements
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(new CompilationInput("wf", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:labos="urn:labos:workflow-runtime:v1">
                  <bpmn:process id="p" isExecutable="true">
                    <bpmn:startEvent id="s"/>
                    <bpmn:receiveTask id="w1">
                      <bpmn:extensionElements>
                        <labos:wait correlationKey="k1"/>
                        <labos:retry count="3"/>
                      </bpmn:extensionElements>
                    </bpmn:receiveTask>
                    <bpmn:endEvent id="e"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="w1" targetRef="e"/>
                  </bpmn:process>
                </bpmn:definitions>
                """, Map.of())));

        // Unknown vendor extension inside receiveTask extensionElements
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(new CompilationInput("wf", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:labos="urn:labos:workflow-runtime:v1"
                                  xmlns:vendor="urn:example:vendor">
                  <bpmn:process id="p" isExecutable="true">
                    <bpmn:startEvent id="s"/>
                    <bpmn:receiveTask id="w1">
                      <bpmn:extensionElements>
                        <labos:wait correlationKey="k1"/>
                        <vendor:hook name="x"/>
                      </bpmn:extensionElements>
                    </bpmn:receiveTask>
                    <bpmn:endEvent id="e"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="w1" targetRef="e"/>
                  </bpmn:process>
                </bpmn:definitions>
                """, Map.of())));

        // Unknown vendor extension inside serviceTask extensionElements
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(new CompilationInput("wf", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:camunda="http://camunda.org/schema/1.0/bpmn">
                  <bpmn:process id="p" isExecutable="true">
                    <bpmn:startEvent id="s"/>
                    <bpmn:serviceTask id="t1" protocolName="HTTP" resource="1">
                      <bpmn:extensionElements><camunda:executionListener event="start"/></bpmn:extensionElements>
                    </bpmn:serviceTask>
                    <bpmn:endEvent id="e"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="t1"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="t1" targetRef="e"/>
                  </bpmn:process>
                </bpmn:definitions>
                """, Map.of())));

        // Standard BPMN documentation + BPMN DI elements alongside valid labos:wait must pass.
        GraphPlan withDi = compiler.compile(new CompilationInput("wf", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                                  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                                  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                                  xmlns:labos="urn:labos:workflow-runtime:v1">
                  <bpmn:process id="p" isExecutable="true">
                    <bpmn:documentation>Wait for sample delivery</bpmn:documentation>
                    <bpmn:startEvent id="s"/>
                    <bpmn:receiveTask id="w1" name="Await Sample">
                      <bpmn:documentation>Non-executing description</bpmn:documentation>
                      <bpmn:extensionElements>
                        <labos:wait correlationKey="sample-di-1"/>
                      </bpmn:extensionElements>
                    </bpmn:receiveTask>
                    <bpmn:endEvent id="e"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="w1" targetRef="e"/>
                  </bpmn:process>
                  <bpmndi:BPMNDiagram id="diag1">
                    <bpmndi:BPMNPlane id="plane1" bpmnElement="p">
                      <bpmndi:BPMNShape id="shape_w1" bpmnElement="w1">
                        <dc:Bounds x="100" y="100" width="100" height="80"/>
                      </bpmndi:BPMNShape>
                      <bpmndi:BPMNEdge id="edge_f1" bpmnElement="f1">
                        <di:waypoint x="50" y="140"/>
                        <di:waypoint x="100" y="140"/>
                      </bpmndi:BPMNEdge>
                    </bpmndi:BPMNPlane>
                  </bpmndi:BPMNDiagram>
                </bpmn:definitions>
                """, Map.of()));
        assertEquals(GraphPlan.SCHEMA_VERSION_V2, withDi.schemaVersion());
        assertEquals("sample-di-1", node(withDi, "w1").waitSpec().correlationKey());
    }

    @Test
    void rejectsNonIntegerOrMissingSchemaVersionInCodec() {
        String validV2 = GraphPlanCodec.toJson(compile("""
                <bpmn:startEvent id="s"/>
                <bpmn:receiveTask id="w1">
                  <bpmn:extensionElements><labos:wait correlationKey="k1"/></bpmn:extensionElements>
                </bpmn:receiveTask>
                <bpmn:endEvent id="e"/>
                <bpmn:sequenceFlow id="f1" sourceRef="s" targetRef="w1"/>
                <bpmn:sequenceFlow id="f2" sourceRef="w1" targetRef="e"/>
                """));

        for (String badSchemaValue : new String[]{"\"1\"", "\"2\"", "1.5", "2.0", "null", "true", "0", "-1"}) {
            String mutated = validV2.replace("\"schemaVersion\":2", "\"schemaVersion\":" + badSchemaValue);
            assertThrows(IllegalArgumentException.class,
                    () -> GraphPlanCodec.fromJson(mutated, "test"),
                    "Should reject schemaVersion=" + badSchemaValue);
        }

        IllegalArgumentException missingEx = assertThrows(IllegalArgumentException.class,
                () -> GraphPlanCodec.fromJson("{}", "run=x"));
        assertInstanceOf(java.io.IOException.class, missingEx.getCause());
    }

    @Test
    void enforcesWaitOutgoingEdgeCompletenessInGraphPlanAndCodec() {
        GraphPlan.Node s = new GraphPlan.Node("s", "s", GraphPlan.Kind.START, "startEvent",
                null, null, null, null, null, null);
        GraphPlan.Node e1 = new GraphPlan.Node("e1", "e1", GraphPlan.Kind.END, "endEvent",
                null, null, null, null, null, null);
        GraphPlan.Node e2 = new GraphPlan.Node("e2", "e2", GraphPlan.Kind.END, "endEvent",
                null, null, null, null, null, null);
        GraphPlan.Edge sToW = new GraphPlan.Edge("f0", "s", "w1", null, false);

        GraphPlan.Node msgWait = new GraphPlan.Node("w1", "w1", GraphPlan.Kind.WAIT, "receiveTask",
                null, null, null, null, null, null,
                new GraphPlan.WaitSpec(GraphPlan.WaitKind.MESSAGE, "k1", null, null));
        // MESSAGE WAIT with 0 NORMAL edges -> rejected
        assertThrows(IllegalArgumentException.class, () -> new GraphPlan(
                2, "v1", "k", "1", "sha", List.of(s, msgWait, e1), List.of(sToW)));
        // MESSAGE WAIT with 2 NORMAL edges -> rejected
        assertThrows(IllegalArgumentException.class, () -> new GraphPlan(
                2, "v1", "k", "1", "sha", List.of(s, msgWait, e1, e2), List.of(
                        sToW,
                        new GraphPlan.Edge("fn1", "w1", "e1", null, false, GraphPlan.RouteKind.NORMAL),
                        new GraphPlan.Edge("fn2", "w1", "e2", null, false, GraphPlan.RouteKind.NORMAL))));

        GraphPlan.Node timerWait = new GraphPlan.Node("w1", "w1", GraphPlan.Kind.WAIT, "intermediateCatchEvent",
                null, null, null, null, null, null,
                new GraphPlan.WaitSpec(GraphPlan.WaitKind.TIMER, null, "PT5S", 5000L));
        // TIMER WAIT with 0 edges -> rejected
        assertThrows(IllegalArgumentException.class, () -> new GraphPlan(
                2, "v1", "k", "1", "sha", List.of(s, timerWait, e1), List.of(sToW)));

        GraphPlan.Node timeoutWait = new GraphPlan.Node("w1", "w1", GraphPlan.Kind.WAIT, "receiveTask",
                null, null, null, null, null, null,
                new GraphPlan.WaitSpec(GraphPlan.WaitKind.MESSAGE_WITH_TIMEOUT, "k1", "PT10S", 10_000L));
        // MESSAGE_WITH_TIMEOUT missing TIMEOUT edge -> rejected
        assertThrows(IllegalArgumentException.class, () -> new GraphPlan(
                2, "v1", "k", "1", "sha", List.of(s, timeoutWait, e1, e2), List.of(
                        sToW,
                        new GraphPlan.Edge("fn", "w1", "e1", null, false, GraphPlan.RouteKind.NORMAL))));
        // MESSAGE_WITH_TIMEOUT missing NORMAL edge (only TIMEOUT) -> rejected
        assertThrows(IllegalArgumentException.class, () -> new GraphPlan(
                2, "v1", "k", "1", "sha", List.of(s, timeoutWait, e1, e2), List.of(
                        sToW,
                        new GraphPlan.Edge("ft", "w1", "e2", null, false, GraphPlan.RouteKind.TIMEOUT))));
        // MESSAGE_WITH_TIMEOUT with 2 TIMEOUT edges -> rejected
        assertThrows(IllegalArgumentException.class, () -> new GraphPlan(
                2, "v1", "k", "1", "sha", List.of(s, timeoutWait, e1, e2), List.of(
                        sToW,
                        new GraphPlan.Edge("fn", "w1", "e1", null, false, GraphPlan.RouteKind.NORMAL),
                        new GraphPlan.Edge("ft1", "w1", "e2", null, false, GraphPlan.RouteKind.TIMEOUT),
                        new GraphPlan.Edge("ft2", "w1", "e2", null, false, GraphPlan.RouteKind.TIMEOUT))));
        // MESSAGE_WITH_TIMEOUT sharing target between NORMAL and TIMEOUT -> rejected
        assertThrows(IllegalArgumentException.class, () -> new GraphPlan(
                2, "v1", "k", "1", "sha", List.of(s, timeoutWait, e1), List.of(
                        sToW,
                        new GraphPlan.Edge("fn", "w1", "e1", null, false, GraphPlan.RouteKind.NORMAL),
                        new GraphPlan.Edge("ft", "w1", "e1", null, false, GraphPlan.RouteKind.TIMEOUT))));
    }
}
