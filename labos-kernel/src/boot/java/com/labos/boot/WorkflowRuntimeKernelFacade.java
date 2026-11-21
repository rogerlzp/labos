package com.labos.boot;

import com.labos.kernel.workflow.runtime.coordinator.WaitOutcomes;
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

    public boolean isHostEnabled() {
        return runtimeHostProvider.getIfAvailable() != null;
    }

    public void startRun(long kernelTenantId, UUID runId) {
        EmbeddedWorkflowRuntimeHost host = requireRunningHost();
        host.startRun(tenants.resolve(kernelTenantId), runId);
    }

    public UUID requestPause(long kernelTenantId, UUID runId) {
        return repository.requestDrain(tenants.resolve(kernelTenantId), runId, "PAUSE");
    }

    public void resumeRun(long kernelTenantId, UUID runId) {
        EmbeddedWorkflowRuntimeHost host = requireRunningHost();
        host.resumeRun(tenants.resolve(kernelTenantId), runId);
    }

    public UUID requestStop(long kernelTenantId, UUID runId) {
        return repository.requestDrain(tenants.resolve(kernelTenantId), runId, "STOP");
    }

    /**
     * Ingest an external G5 Wait signal through the durable idempotent Inbox contract
     * ({@link WorkflowRuntimeRepository#receiveWaitInbox}). When a signal synchronously
     * satisfies an active Wait and the embedded host is running, triggers a best-effort
     * immediate wakeup so the successor Token runs without waiting for the next poll tick.
     */
    public WorkflowRuntimeRepository.GraphWaitResult receiveWaitSignal(
            long kernelTenantId, UUID runId, String source, String sourceMessageId,
            String correlationKey, String payloadJson) {
        UUID runtimeTenant = tenants.resolve(kernelTenantId);
        WorkflowRuntimeRepository.GraphWaitResult result = repository.receiveWaitInbox(
                runtimeTenant, runId, source, sourceMessageId, correlationKey, payloadJson);
        if (WaitOutcomes.COMPLETED.equals(result.outcome())) {
            EmbeddedWorkflowRuntimeHost host = runtimeHostProvider.getIfAvailable();
            if (host != null) {
                try {
                    host.wakeRunIfRunning(runtimeTenant, runId);
                } catch (RuntimeException ignored) {
                    // Durable DB state (WAIT_SATISFIED + READY successor Token) is already committed;
                    // the dispatch loop or subsequent startup will advance the Run safely.
                }
            }
        }
        return result;
    }

    public Optional<WorkflowRuntimeRepository.RunOverview> getRunOverview(long kernelTenantId, UUID runId) {
        return repository.findRunOverview(tenants.resolve(kernelTenantId), runId);
    }

    private EmbeddedWorkflowRuntimeHost requireRunningHost() {
        EmbeddedWorkflowRuntimeHost host = runtimeHostProvider.getIfAvailable();
        if (host == null) {
            throw new IllegalStateException(
                    "Embedded WorkflowRuntimeHost is disabled (" + WorkflowRuntimeHostProperties.PROP_ENABLED + "=false)");
        }
        return host;
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
