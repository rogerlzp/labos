package com.labos.kernel.workflow.runtime.worker;

import java.util.UUID;

/**
 * Controlled execution context passed to a {@link WorkerTaskHandler}.
 *
 * <p><strong>Synchronous, owning-thread contract:</strong> this context is strictly bound to
 * the single {@link WorkerPool} thread executing the handler and to the active duration of
 * {@link WorkerTaskHandler#execute}. Handlers MUST NOT hand this context to background threads
 * or use it after returning; off-thread and post-return side-effect calls throw
 * {@link IllegalStateException} before touching persistence.
 *
 * <p>Handlers never receive direct access to {@code WorkflowRuntimeRepository}.
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
     * Create (or idempotently replay) the single immutable {@code command_ledger} entry and
     * {@code DEVICE_COMMAND} Outbox row for this {@code STARTED} Attempt.
     * Requires the Run to be {@code RUNNING + OPEN} unless replaying the identical payload.
     */
    UUID createDeviceCommand(String deviceId, String commandKind, String idempotencyClass, String payloadJson);

    /**
     * Best-effort process-local cancellation indicator (local registry cancellation or thread interrupt).
     * Observing {@code true} does not prove that any already-started external side effect stopped.
     */
    boolean isCancellationRequested();
}
