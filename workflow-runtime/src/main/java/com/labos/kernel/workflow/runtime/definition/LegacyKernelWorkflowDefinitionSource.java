package com.labos.kernel.workflow.runtime.definition;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Adapter boundary for the existing Kernel bigint-tenant workflow store.
 * The Kernel supplies the reader using its MyBatis mappers; this adapter never queries
 * workflow_node/workflow_edge itself and never reconstructs control flow from edge projections.
 */
public final class LegacyKernelWorkflowDefinitionSource implements WorkflowDefinitionSource {
    public static final String TENANT_KEY_PREFIX = "kernel-tenant:";

    @FunctionalInterface
    public interface TenantKeyLookup {
        String findTenantKey(UUID runtimeTenantId);
    }

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

    private final TenantKeyLookup tenantKeys;
    private final LegacyWorkflowReader reader;

    public LegacyKernelWorkflowDefinitionSource(TenantKeyLookup tenantKeys, LegacyWorkflowReader reader) {
        this.tenantKeys=Objects.requireNonNull(tenantKeys,"tenantKeys");
        this.reader=Objects.requireNonNull(reader,"reader");
    }

    @Override
    public WorkflowDefinition load(UUID runtimeTenantId, long workflowId) {
        Objects.requireNonNull(runtimeTenantId,"runtimeTenantId");
        String key=tenantKeys.findTenantKey(runtimeTenantId);
        if(key==null || !key.startsWith(TENANT_KEY_PREFIX))
            throw new IllegalStateException("Runtime tenant is not mapped to a legacy Kernel tenant");
        long legacyTenantId;
        try {
            legacyTenantId=Long.parseLong(key.substring(TENANT_KEY_PREFIX.length()));
        } catch(NumberFormatException e) {
            throw new IllegalStateException("Malformed legacy Kernel tenant key: "+key,e);
        }
        if(legacyTenantId<=0) throw new IllegalStateException("Legacy Kernel tenant id must be positive");
        LegacyDefinition definition=reader.load(legacyTenantId,workflowId);
        if(definition==null) return null;
        return new WorkflowDefinition(runtimeTenantId,workflowId,definition.version(),
                definition.bpmnXml(),definition.nodeMetadata());
    }
}
