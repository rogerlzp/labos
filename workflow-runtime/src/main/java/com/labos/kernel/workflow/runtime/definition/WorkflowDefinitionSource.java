package com.labos.kernel.workflow.runtime.definition;

import java.util.Map;
import java.util.Objects;

/**
 * Read-only port to the authoritative Kernel workflow definition store.
 * legacyTenantId is only the existing Kernel data-scope key used for legacy SQL reads;
 * it is not converted into a Runtime tenant and is not a login-user identifier.
 */
public interface WorkflowDefinitionSource {
    WorkflowDefinition load(long legacyTenantId, long workflowId);

    record NodeMetadata(String capabilityCode, Long workflowNodeId) {}
    record WorkflowDefinition(long workflowId, String version,
                              String bpmnXml, Map<String, NodeMetadata> nodeMetadata) {
        public WorkflowDefinition {
            if(workflowId<=0) throw new IllegalArgumentException("workflowId must be positive");
            if(version==null || version.isBlank()) throw new IllegalArgumentException("definition version is required");
            if(bpmnXml==null || bpmnXml.isBlank()) throw new IllegalArgumentException("BPMN XML is required");
            nodeMetadata=Map.copyOf(Objects.requireNonNull(nodeMetadata,"nodeMetadata"));
        }
    }
}
