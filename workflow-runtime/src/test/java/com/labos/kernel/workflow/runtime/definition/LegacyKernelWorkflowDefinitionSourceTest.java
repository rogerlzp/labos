package com.labos.kernel.workflow.runtime.definition;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class LegacyKernelWorkflowDefinitionSourceTest {
    @Test void readsLegacyDefinitionWithoutCreatingRuntimeTenantMapping() {
        AtomicLong seenTenant=new AtomicLong();
        LegacyKernelWorkflowDefinitionSource source=new LegacyKernelWorkflowDefinitionSource(
                (tenant,workflow) -> {
                    seenTenant.set(tenant);
                    return new LegacyKernelWorkflowDefinitionSource.LegacyDefinition("v9",
                            "<bpmn:definitions/>",Map.of("n",new WorkflowDefinitionSource.NodeMetadata("CAP",11L)));
                });
        var definition=source.load(73,901);
        assertEquals(73L,seenTenant.get());
        assertEquals(901L,definition.workflowId());
        assertEquals("v9",definition.version());
        assertEquals("CAP",definition.nodeMetadata().get("n").capabilityCode());
    }

    @Test void rejectsInvalidLegacyScopeAndWorkflowIds() {
        var source=new LegacyKernelWorkflowDefinitionSource((tenant,workflow)->null);
        assertThrows(IllegalArgumentException.class,()->source.load(0,1));
        assertThrows(IllegalArgumentException.class,()->source.load(1,0));
    }
}
