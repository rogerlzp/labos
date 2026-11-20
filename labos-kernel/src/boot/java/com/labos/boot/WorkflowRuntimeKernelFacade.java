package com.labos.boot;

import com.labos.kernel.workflow.runtime.definition.WorkflowRunFactory;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeScope;

import java.util.Objects;

/**
 * Kernel-facing seam. The authenticated Kernel context supplies its legacy data-scope
 * and actor identity; the Runtime itself always uses one fixed system scope.
 */
public final class WorkflowRuntimeKernelFacade {
    private final WorkflowRunFactory runs;

    public WorkflowRuntimeKernelFacade(WorkflowRunFactory runs) {
        this.runs = Objects.requireNonNull(runs, "runs");
    }

    public WorkflowRuntimeRepository.CreatedRun createRun(
            long kernelDataScopeId, long workflowId, String requestId,
            String inputJson, String canonicalRequestJson) {
        return runs.create(WorkflowRuntimeScope.SINGLETON_TENANT_ID, kernelDataScopeId,
                workflowId, requestId, inputJson, canonicalRequestJson);
    }
}
