package com.labos.boot;

import com.labos.kernel.workflow.runtime.definition.WorkflowRunFactory;
import com.labos.kernel.workflow.runtime.host.EmbeddedWorkflowRuntimeHost;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Kernel-facing application seam for creating Runs, querying observability snapshots,
 * and invoking T21 manual reconciliation for the embedded Workflow Runtime.
 *
 * <p>Owns the legacy bigint tenant -> stable Runtime UUID bridge so callers cannot
 * accidentally invent or cast tenant identifiers. When {@code labos.workflow.runtime.host.enabled}
 * is {@code false} (default), {@link #hostHealth()} returns {@link Optional#empty()} and no
 * background host threads or TCP connections run.
 */
public final class WorkflowRuntimeKernelFacade {
    private final WorkflowRuntimeKernelConfiguration.WorkflowRuntimeTenantResolver tenants;
    private final WorkflowRunFactory runs;
    private final WorkflowRuntimeRepository repository;
    private final ObjectProvider<EmbeddedWorkflowRuntimeHost> runtimeHostProvider;

    public WorkflowRuntimeKernelFacade(
            WorkflowRuntimeKernelConfiguration.WorkflowRuntimeTenantResolver tenants,
            WorkflowRunFactory runs,
            WorkflowRuntimeRepository repository,
            ObjectProvider<EmbeddedWorkflowRuntimeHost> runtimeHostProvider) {
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.runtimeHostProvider = Objects.requireNonNull(runtimeHostProvider, "runtimeHostProvider");
    }

    public WorkflowRuntimeRepository.CreatedRun createRun(
            long kernelTenantId, long workflowId, String requestId,
            String inputJson, String canonicalRequestJson) {
        return runs.create(tenants.resolve(kernelTenantId), workflowId,
                requestId, inputJson, canonicalRequestJson);
    }

    public Optional<EmbeddedWorkflowRuntimeHost.HealthSnapshot> hostHealth() {
        EmbeddedWorkflowRuntimeHost host = runtimeHostProvider.getIfAvailable();
        return host == null ? Optional.empty() : Optional.of(host.healthSnapshot());
    }

    public List<WorkflowRuntimeRepository.ManualRequiredRunSummary> listManualRequiredRuns(
            long kernelTenantId, int limit) {
        return repository.findManualRequiredRuns(tenants.resolve(kernelTenantId), limit);
    }

    public List<WorkflowRuntimeRepository.UnknownCommandSummary> listUnknownCommands(
            long kernelTenantId, int limit) {
        return repository.findUnknownCommands(tenants.resolve(kernelTenantId), limit);
    }

    public boolean reconcileUnknownCommand(
            long kernelTenantId, UUID commandId, String resolvedState,
            String deviceSequence, String evidenceJson, String actor) {
        UUID runtimeTenant = tenants.resolve(kernelTenantId);
        return repository.resolveUnknownCommand(
                runtimeTenant, commandId, resolvedState, deviceSequence,
                (evidenceJson == null || evidenceJson.isBlank()) ? "{}" : evidenceJson, actor);
    }
}
