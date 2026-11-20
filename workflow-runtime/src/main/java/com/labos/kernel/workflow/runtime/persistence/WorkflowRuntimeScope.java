package com.labos.kernel.workflow.runtime.persistence;

import java.util.UUID;

/**
 * LabOS is deployed as one shared workflow runtime scope.
 * This is an internal database partition key, not a login user, organization role,
 * or authorization boundary. User identity and permissions remain in Kernel security.
 */
public final class WorkflowRuntimeScope {
    public static final UUID SINGLETON_TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private WorkflowRuntimeScope() {}
}
