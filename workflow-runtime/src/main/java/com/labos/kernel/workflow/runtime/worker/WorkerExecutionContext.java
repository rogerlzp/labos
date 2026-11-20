package com.labos.kernel.workflow.runtime.worker;

import java.util.UUID;

/**
 * Controlled execution context passed to a {@link WorkerTaskHandler}.
 *
 * <p>Handlers never receive direct access to {@code WorkflowRuntimeRepository}.
 * Every side-effect fact recorded through this context is strictly bound to
 * the owning {@code (tenantId, runId, attemptId, workerId)} and is rejected
 * once the handler invocation exits its active execution scope.
 */
public interface WorkerExecutionContext {
    UUID tenantId();

    UUID runId();

    UUID attemptId();

    String workerId();

    String nodeKey();

    /**
     * Record a durable {@code SIDE_EFFECT_STARTED} fact for {@code sideEffectKey}.
     * Requires the Run to be {@code RUNNING + OPEN} unless replaying an identical existing fact.
     */
    boolean recordSideEffectStarted(String sideEffectKey, String factKey, String payloadJson);

    /**
     * Record a durable {@code SIDE_EFFECT_COMPLETED} fact closing {@code sideEffectKey}.
     * Allowed during active Drain for operations that already started before gate closure.
     */
    boolean recordSideEffectCompleted(String sideEffectKey, String factKey, String payloadJson);

    /**
     * Record a durable {@code SIDE_EFFECT_UNKNOWN} fact for an uncertain outcome of {@code sideEffectKey}.
     * Allowed during active Drain for operations that already started before gate closure.
     */
    boolean recordSideEffectUnknown(String sideEffectKey, String factKey, String payloadJson);

    /**
     * Best-effort process-local cancellation indicator (local registry cancellation or thread interrupt).
     * Observing {@code true} does not prove that any already-started external side effect stopped.
     */
    boolean isCancellationRequested();
}
