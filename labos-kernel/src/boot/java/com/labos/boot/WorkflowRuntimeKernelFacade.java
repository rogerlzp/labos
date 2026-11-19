package com.labos.boot;

import com.labos.kernel.workflow.runtime.definition.WorkflowRunFactory;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;

import java.util.Objects;

/**
 * Kernel-facing application seam for creating a Runtime Run from a legacy workflow.
 * It owns the legacy bigint tenant -> stable Runtime UUID bridge so callers cannot
 * accidentally invent or cast tenant identifiers.
 *
 * <p>This facade creates the durable Run + immutable GraphPlan snapshot. It does not
 * start execution: RuntimeHost/GraphCoordinator/dispatch adapters are deliberately
 * not wired until their execution and recovery contracts are complete.
 */
public final class WorkflowRuntimeKernelFacade {
    private final WorkflowRuntimeKernelConfiguration.WorkflowRuntimeTenantResolver tenants;
    private final WorkflowRunFactory runs;

    public WorkflowRuntimeKernelFacade(
            WorkflowRuntimeKernelConfiguration.WorkflowRuntimeTenantResolver tenants,
            WorkflowRunFactory runs) {
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.runs = Objects.requireNonNull(runs, "runs");
    }

    public WorkflowRuntimeRepository.CreatedRun createRun(
            long kernelTenantId, long workflowId, String requestId,
            String inputJson, String canonicalRequestJson) {
        return runs.create(tenants.resolve(kernelTenantId), workflowId,
                requestId, inputJson, canonicalRequestJson);
    }
}
