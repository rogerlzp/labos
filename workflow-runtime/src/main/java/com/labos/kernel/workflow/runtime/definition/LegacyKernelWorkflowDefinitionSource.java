package com.labos.kernel.workflow.runtime.definition;

import java.util.Map;
import java.util.Objects;

/**
 * Adapter to the existing Kernel bigint-scoped workflow store.
 * The legacy scope is used only to read the BPMN definition; it is not mapped
 * to or used as a Runtime tenant identity.
 */
public final class LegacyKernelWorkflowDefinitionSource implements WorkflowDefinitionSource {
    @FunctionalInterface
    public interface LegacyWorkflowReader {
        LegacyDefinition load(long legacyTenantId, long workflowId);
    }

    public record LegacyDefinition(String version, String bpmnXml, Map<String, NodeMetadata> nodeMetadata) {
        public LegacyDefinition {
            if(version==null || version.isBlank()) throw new IllegalArgumentException("definition version is required");
            if(bpmnXml==null || bpmnXml.isBlank()) throw new IllegalArgumentException("BPMN XML is required");
            nodeMetadata=Map.copyOf(Objects.requireNonNull(nodeMetadata,"nodeMetadata"));
        }
    }

    private final LegacyWorkflowReader reader;

    public LegacyKernelWorkflowDefinitionSource(LegacyWorkflowReader reader) {
        this.reader=Objects.requireNonNull(reader,"reader");
    }

    @Override
    public WorkflowDefinition load(long legacyTenantId, long workflowId) {
        if(legacyTenantId<=0) throw new IllegalArgumentException("legacy Kernel data scope must be positive");
        if(workflowId<=0) throw new IllegalArgumentException("workflowId must be positive");
        LegacyDefinition definition=reader.load(legacyTenantId,workflowId);
        if(definition==null) return null;
        return new WorkflowDefinition(workflowId,definition.version(),definition.bpmnXml(),definition.nodeMetadata());
    }
}
