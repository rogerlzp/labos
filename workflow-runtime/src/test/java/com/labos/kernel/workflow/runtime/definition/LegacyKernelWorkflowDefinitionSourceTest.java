package com.labos.kernel.workflow.runtime.definition;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class LegacyKernelWorkflowDefinitionSourceTest {
    @Test void mapsStableTenantKeyAndReadsOriginalBpmn() {
        UUID runtimeTenant=UUID.randomUUID();
        AtomicLong seenTenant=new AtomicLong();
        LegacyKernelWorkflowDefinitionSource source=new LegacyKernelWorkflowDefinitionSource(
                id -> LegacyKernelWorkflowDefinitionSource.TENANT_KEY_PREFIX+"73",
                (tenant,workflow) -> {
                    seenTenant.set(tenant);
                    return new LegacyKernelWorkflowDefinitionSource.LegacyDefinition("v9",
                            "<bpmn:definitions/>",Map.of("n",new WorkflowDefinitionSource.NodeMetadata("CAP",11L)));
                });
        var definition=source.load(runtimeTenant,901);
        assertEquals(73L,seenTenant.get());
        assertEquals(runtimeTenant,definition.runtimeTenantId());
        assertEquals(901L,definition.workflowId());
        assertEquals("v9",definition.version());
        assertEquals("CAP",definition.nodeMetadata().get("n").capabilityCode());
    }

    @Test void refusesUnmappedOrMalformedTenantKeys() {
        UUID runtimeTenant=UUID.randomUUID();
        var unmapped=new LegacyKernelWorkflowDefinitionSource(id -> "other-tenant",
                (tenant,workflow)->null);
        assertThrows(IllegalStateException.class,()->unmapped.load(runtimeTenant,1));
        var malformed=new LegacyKernelWorkflowDefinitionSource(id -> "kernel-tenant:not-a-number",
                (tenant,workflow)->null);
        assertThrows(IllegalStateException.class,()->malformed.load(runtimeTenant,1));
    }
}
