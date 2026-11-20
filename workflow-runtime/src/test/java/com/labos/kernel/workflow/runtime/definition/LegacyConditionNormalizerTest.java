package com.labos.kernel.workflow.runtime.definition;

import com.labos.kernel.workflow.runtime.graph.BpmnGraphCompiler;
import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import com.labos.kernel.workflow.runtime.graph.GraphPlanCodec;
import com.labos.kernel.workflow.runtime.graph.RestrictedExpressionEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LegacyConditionNormalizerTest {

    @Test
    void normalizesStrictIdentifierPlaceholdersAndKeepsBareExpressionsIntact() {
        assertEquals("mode == 'A'", LegacyConditionNormalizer.normalize("${mode} == 'A'"));
        assertEquals("score >= 80 && ready", LegacyConditionNormalizer.normalize("${score} >= 80 && ${ready}"));
        assertEquals("readPlate.od > 1.0", LegacyConditionNormalizer.normalize("${readPlate.od} > 1.0"));
        assertEquals("!flag || count != 0", LegacyConditionNormalizer.normalize("!${flag} || ${count} != 0"));
        assertEquals("mode == 'A'", LegacyConditionNormalizer.normalize("mode == 'A'"));
        assertNull(LegacyConditionNormalizer.normalize(null));
    }

    @Test
    void preservesQuotedStringLiteralsContainingDollarBrace() {
        assertEquals("mode == '${keep}'", LegacyConditionNormalizer.normalize("${mode} == '${keep}'"));
        assertEquals("mode == \"${keep}\"", LegacyConditionNormalizer.normalize("${mode} == \"${keep}\""));
        assertEquals("msg == 'it\\'s ${literal}' && ok",
                LegacyConditionNormalizer.normalize("${msg} == 'it\\'s ${literal}' && ${ok}"));
    }

    @Test
    void leavesNonIdentifierPlaceholdersUntouchedSoEvaluatorFailsClosed() {
        List<String> rejected = List.of(
                "${1+2} == 3",
                "${System.exit(0)}",
                "${a[0]} == 1",
                "${} == 1",
                "${ mode } == 'A'",
                "${true}",
                "${false}",
                "${null}",
                "${a.} == 1",
                "${.a} == 1",
                "foo${bar} == 1",
                "${bar}baz == 1"
        );
        for (String expr : rejected) {
            String normalized = LegacyConditionNormalizer.normalize(expr);
            assertEquals(expr, normalized, "Unsafe placeholder must not be rewritten: " + expr);
            assertThrows(IllegalArgumentException.class,
                    () -> RestrictedExpressionEvaluator.validateSyntax(normalized),
                    "RestrictedExpressionEvaluator must fail closed on: " + expr);
        }
    }

    @Test
    void restrictedExpressionEvaluatorItselfStillRejectsDollarBraceSyntax() {
        assertThrows(IllegalArgumentException.class,
                () -> RestrictedExpressionEvaluator.validateSyntax("${flag}"));
        assertThrows(IllegalArgumentException.class,
                () -> RestrictedExpressionEvaluator.evaluateBoolean("${flag}", Map.of("flag", true)));
        assertThrows(IllegalArgumentException.class,
                () -> RestrictedExpressionEvaluator.evaluateBoolean("${mode} == 'A'", Map.of("mode", "A")));
    }

    @Test
    void normalizePlanPreservesOriginalBpmnSha256VersionAndNodes() {
        String originalXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="P" isExecutable="true">
                    <bpmn:startEvent id="s"/>
                    <bpmn:exclusiveGateway id="gw" default="e_def"/>
                    <bpmn:task id="t_a" protocolName="OpA" resource="10"/>
                    <bpmn:task id="t_b" protocolName="OpB" resource="10"/>
                    <bpmn:exclusiveGateway id="m"/>
                    <bpmn:endEvent id="end"/>
                    <bpmn:sequenceFlow id="e0" sourceRef="s" targetRef="gw"/>
                    <bpmn:sequenceFlow id="e_a" sourceRef="gw" targetRef="t_a">
                      <bpmn:conditionExpression>${mode} == 'A'</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="e_def" sourceRef="gw" targetRef="t_b"/>
                    <bpmn:sequenceFlow id="e1" sourceRef="t_a" targetRef="m"/>
                    <bpmn:sequenceFlow id="e2" sourceRef="t_b" targetRef="m"/>
                    <bpmn:sequenceFlow id="e3" sourceRef="m" targetRef="end"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        BpmnGraphCompiler compiler = new BpmnGraphCompiler();
        GraphPlan rawPlan = compiler.compile(new BpmnGraphCompiler.CompilationInput(
                "tenant:42", "v7", originalXml, Map.of()));
        String expectedSha = GraphPlanCodec.sha256(originalXml);
        assertEquals(expectedSha, rawPlan.bpmnSha256());

        GraphPlan normalized = LegacyConditionNormalizer.normalizePlan(rawPlan);
        assertEquals(expectedSha, normalized.bpmnSha256(), "bpmnSha256 must stay bound to original XML");
        assertEquals("v7", normalized.definitionVersion());
        assertEquals("tenant:42", normalized.definitionKey());
        assertEquals(rawPlan.nodes(), normalized.nodes());
        String edgeCondition = normalized.edges().stream()
                .filter(e -> "e_a".equals(e.key()))
                .findFirst().orElseThrow()
                .condition();
        assertEquals("mode == 'A'", edgeCondition);
        assertTrue(RestrictedExpressionEvaluator.evaluateBoolean(edgeCondition, Map.of("mode", "A")));
        assertFalse(RestrictedExpressionEvaluator.evaluateBoolean(edgeCondition, Map.of("mode", "B")));
    }

    @Test
    void legacyKernelDefinitionSourceMarksNormalizeFlagWithoutMutatingXml() {
        UUID runtimeTenant = UUID.randomUUID();
        String rawXml = "<bpmn:definitions><bpmn:conditionExpression>${x} == 1</bpmn:conditionExpression></bpmn:definitions>";
        LegacyKernelWorkflowDefinitionSource source = new LegacyKernelWorkflowDefinitionSource(
                id -> LegacyKernelWorkflowDefinitionSource.TENANT_KEY_PREFIX + "9",
                (t, wf) -> new LegacyKernelWorkflowDefinitionSource.LegacyDefinition("v3", rawXml, Map.of()));
        WorkflowDefinitionSource.WorkflowDefinition loaded = source.load(runtimeTenant, 123L);
        assertNotNull(loaded);
        assertTrue(loaded.normalizeLegacyConditions());
        assertEquals(rawXml, loaded.bpmnXml(), "Legacy adapter must preserve original BPMN XML verbatim");
    }
}
