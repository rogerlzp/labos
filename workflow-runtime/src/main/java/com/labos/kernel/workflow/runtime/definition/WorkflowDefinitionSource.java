package com.labos.kernel.workflow.runtime.definition;

import java.util.Map;
import java.util.UUID;
import java.util.Objects;

/**
 * Read-only port to the authoritative Kernel workflow definition store.
 * Implementations must scope every read by tenant and workflow id and return the original BPMN XML.
 */
public interface WorkflowDefinitionSource {
    WorkflowDefinition load(UUID runtimeTenantId, long workflowId);

    record NodeMetadata(String capabilityCode, Long workflowNodeId) {}
    record WorkflowDefinition(UUID runtimeTenantId, long workflowId, String version,
                              String bpmnXml, Map<String, NodeMetadata> nodeMetadata,
                              boolean normalizeLegacyConditions) {
        public WorkflowDefinition {
            Objects.requireNonNull(runtimeTenantId, "runtimeTenantId");
            if(workflowId<=0) throw new IllegalArgumentException("workflowId must be positive");
            if(version==null || version.isBlank()) throw new IllegalArgumentException("definition version is required");
            if(bpmnXml==null || bpmnXml.isBlank()) throw new IllegalArgumentException("BPMN XML is required");
            nodeMetadata=Map.copyOf(Objects.requireNonNull(nodeMetadata,"nodeMetadata"));
        }

        public WorkflowDefinition(UUID runtimeTenantId, long workflowId, String version,
                                  String bpmnXml, Map<String, NodeMetadata> nodeMetadata) {
            this(runtimeTenantId, workflowId, version, bpmnXml, nodeMetadata, false);
        }
    }
}
