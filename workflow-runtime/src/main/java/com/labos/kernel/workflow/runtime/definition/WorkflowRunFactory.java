package com.labos.kernel.workflow.runtime.definition;

import com.labos.kernel.workflow.runtime.graph.BpmnGraphCompiler;
import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import com.labos.kernel.workflow.runtime.graph.GraphPlanCodec;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Creates a Run from the current authoritative BPMN definition.
 * The compiled GraphPlan is persisted by createRun as an immutable definition snapshot;
 * subsequent execution/recovery must use that snapshot, never re-read the mutable BPMN row.
 */
public final class WorkflowRunFactory {
    private final WorkflowDefinitionSource definitions;
    private final BpmnGraphCompiler compiler;
    private final WorkflowRuntimeRepository repository;

    public WorkflowRunFactory(WorkflowDefinitionSource definitions,
                              BpmnGraphCompiler compiler,
                              WorkflowRuntimeRepository repository) {
        this.definitions=Objects.requireNonNull(definitions,"definitions");
        this.compiler=Objects.requireNonNull(compiler,"compiler");
        this.repository=Objects.requireNonNull(repository,"repository");
    }

    public WorkflowRuntimeRepository.CreatedRun create(
            UUID runtimeTenantId, long legacyWorkflowId, String requestId,
            String inputJson, String canonicalRequestJson) {
        Objects.requireNonNull(runtimeTenantId,"runtimeTenantId");
        if(legacyWorkflowId<=0) throw new IllegalArgumentException("workflowId must be positive");
        WorkflowDefinitionSource.WorkflowDefinition source=definitions.load(
                runtimeTenantId,legacyWorkflowId);
        if(source==null) throw new IllegalArgumentException("Workflow definition not found");
        Map<String,BpmnGraphCompiler.NodeMetadata> metadata=source.nodeMetadata().entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e->new BpmnGraphCompiler.NodeMetadata(e.getValue().capabilityCode(),
                                e.getValue().workflowNodeId())));
        GraphPlan plan=compiler.compile(new BpmnGraphCompiler.CompilationInput(
                runtimeTenantId + ":" + source.workflowId(),source.version(),source.bpmnXml(),metadata));
        String firstNode=firstNode(plan);
        byte[] definitionHash=java.util.HexFormat.of().parseHex(plan.bpmnSha256());
        String stableInput=inputJson==null?"{}":inputJson;
        String canonical=canonicalRequestJson==null?stableInput:canonicalRequestJson;
        return repository.createRun(runtimeTenantId,requestId,"BPMN_WORKFLOW",
                canonical,plan.definitionKey(),plan.definitionVersion(),definitionHash,
                plan.compilerVersion(),GraphPlanCodec.toJson(plan),stableInput,firstNode);
    }

    private static String firstNode(GraphPlan plan) {
        String start=plan.nodes().stream().filter(n->n.kind()==GraphPlan.Kind.START)
                .map(GraphPlan.Node::key).findFirst().orElseThrow();
        var outgoing=plan.edges().stream().filter(e->e.from().equals(start)).toList();
        if(outgoing.size()!=1)
            throw new IllegalArgumentException("V1 startEvent must have exactly one outgoing sequenceFlow");
        return outgoing.get(0).to();
    }

}
