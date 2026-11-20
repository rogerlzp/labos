package com.labos.kernel.workflow.runtime.worker;

import com.labos.kernel.workflow.runtime.coordinator.WorkflowRunLifecycleCoordinator;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Application service owning the single-Attempt Worker execution lifecycle on the
 * single embedded Runtime Host.
 *
 * <p>Responsibilities and strict boundaries:
 * <ul>
 *   <li>Runs handler work on {@link WorkerPool} while keeping {@link WorkflowRuntimeRepository}
 *       out of handler reach (handlers receive only a scoped {@link WorkerExecutionContext}).</li>
 *   <li>Enforces durable {@code acceptAttempt -> startAttempt} admission before invoking any handler;
 *       a closed Run gate or stale dispatch aborts cleanly before handler execution.</li>
 *   <li>Settles completed Attempts via {@link WorkflowRunLifecycleCoordinator#completeAttemptAndNotify},
 *       bridging durable completion to {@code GraphCoordinator} advancement while preserving committed
 *       completions during active Drain when the Run mailbox refuses wakeup.</li>
 *   <li>Fails closed whenever a handler returns with an unclosed side effect, fails after recording
 *       a side effect, or is cancelled after crossing a side-effect boundary.</li>
 * </ul>
 */
public final class WorkerExecutionService {
    public enum OutcomeStatus {
        REJECTED_BEFORE_START,
        CANCELLED_BEFORE_START,
        SUCCEEDED,
        FAILED,
        CANCELLED,
        MANUAL_REQUIRED,
        SETTLEMENT_FAILED
    }

    public record ExecutionOutcome(
            UUID attemptId,
            OutcomeStatus status,
            String runOutcome,
            UUID interventionId,
            boolean wakeupScheduled,
            CompletableFuture<Void> graphAdvance,
            Throwable error) {
        public ExecutionOutcome {
            Objects.requireNonNull(attemptId, "attemptId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(graphAdvance, "graphAdvance");
        }

        private static ExecutionOutcome rejectedBeforeStart(UUID attemptId, Throwable error) {
            return new ExecutionOutcome(attemptId, OutcomeStatus.REJECTED_BEFORE_START, null, null,
                    false, CompletableFuture.completedFuture(null), error);
        }

        private static ExecutionOutcome cancelledBeforeStart(UUID attemptId) {
            return new ExecutionOutcome(attemptId, OutcomeStatus.CANCELLED_BEFORE_START, null, null,
                    false, CompletableFuture.completedFuture(null), null);
        }

        private static ExecutionOutcome succeeded(
                UUID attemptId, WorkflowRunLifecycleCoordinator.CompletionDispatch dispatch) {
            return new ExecutionOutcome(attemptId, OutcomeStatus.SUCCEEDED, "SUCCEEDED", null,
                    dispatch.wakeupScheduled(), dispatch.graphAdvance(), null);
        }

        private static ExecutionOutcome settledFailure(
                UUID attemptId, WorkflowRuntimeRepository.AttemptFailureResult result, Throwable cause) {
            OutcomeStatus status = "MANUAL_REQUIRED".equals(result.outcome())
                    ? OutcomeStatus.MANUAL_REQUIRED
                    : OutcomeStatus.FAILED;
            return new ExecutionOutcome(attemptId, status, result.outcome(), result.interventionId(),
                    false, CompletableFuture.completedFuture(null), cause);
        }

        private static ExecutionOutcome settledCancellation(
                UUID attemptId, WorkflowRuntimeRepository.AttemptFailureResult result) {
            OutcomeStatus status = "MANUAL_REQUIRED".equals(result.outcome())
                    ? OutcomeStatus.MANUAL_REQUIRED
                    : OutcomeStatus.CANCELLED;
            return new ExecutionOutcome(attemptId, status, result.outcome(), result.interventionId(),
                    false, CompletableFuture.completedFuture(null), null);
        }

        private static ExecutionOutcome settlementFailed(UUID attemptId, Throwable error) {
            return new ExecutionOutcome(attemptId, OutcomeStatus.SETTLEMENT_FAILED, null, null,
                    false, CompletableFuture.completedFuture(null), error);
        }
    }

    private static final class PendingExecution {
        final CompletableFuture<ExecutionOutcome> outcomeFuture = new CompletableFuture<>();
        final AtomicBoolean entered = new AtomicBoolean();
    }

    private final WorkflowRuntimeRepository repository;
    private final WorkflowRunLifecycleCoordinator lifecycleCoordinator;
    private final WorkerPool workerPool;
    private final WorkerExecutionRegistry executionRegistry;
    private final ConcurrentMap<UUID, PendingExecution> inFlight = new ConcurrentHashMap<>();

    public WorkerExecutionService(
            WorkflowRuntimeRepository repository,
            WorkflowRunLifecycleCoordinator lifecycleCoordinator,
            WorkerPool workerPool,
            WorkerExecutionRegistry executionRegistry) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.lifecycleCoordinator = Objects.requireNonNull(lifecycleCoordinator, "lifecycleCoordinator");
        this.workerPool = Objects.requireNonNull(workerPool, "workerPool");
        this.executionRegistry = Objects.requireNonNull(executionRegistry, "executionRegistry");
    }

    /**
     * Submit an already-dispatched Attempt to {@link WorkerPool}.
     * Pool/gate rejections propagate synchronously so the caller observes backpressure immediately.
     * The returned future completes only after durable DB settlement finishes.
     */
    public CompletableFuture<ExecutionOutcome> submit(
            WorkerTaskHandler.TaskRequest request, WorkerTaskHandler handler) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(handler, "handler");

        PendingExecution pending = new PendingExecution();
        if (inFlight.putIfAbsent(request.attemptId(), pending) != null) {
            throw new IllegalStateException("Attempt already submitted to WorkerExecutionService: "
                    + request.attemptId());
        }
        try {
            workerPool.submit(request.runId(), request.attemptId(), request.workerId(), () -> {
                if (!pending.entered.compareAndSet(false, true)) {
                    return null;
                }
                ExecutionOutcome outcome = null;
                try {
                    outcome = executeAttemptLifecycle(request, handler);
                    return outcome;
                } catch (Throwable unexpected) {
                    outcome = ExecutionOutcome.settlementFailed(request.attemptId(), unexpected);
                    if (unexpected instanceof Error err) throw err;
                    return outcome;
                } finally {
                    inFlight.remove(request.attemptId(), pending);
                    if (outcome != null) {
                        pending.outcomeFuture.complete(outcome);
                    } else if (!pending.outcomeFuture.isDone()) {
                        pending.outcomeFuture.complete(ExecutionOutcome.settlementFailed(
                                request.attemptId(), new IllegalStateException("Worker execution exited without outcome")));
                    }
                }
            });
            return pending.outcomeFuture;
        } catch (RuntimeException | Error submissionFailure) {
            inFlight.remove(request.attemptId(), pending);
            throw submissionFailure;
        }
    }

    /**
     * Best-effort local cancellation for a submitted Attempt.
     * If the Attempt was still queued before execution began, completes its future as
     * {@link OutcomeStatus#CANCELLED_BEFORE_START}; if already executing, lets the worker
     * thread finish durable DB settlement ({@code CANCELLED} or {@code MANUAL_REQUIRED}).
     */
    public boolean requestCancel(UUID attemptId) {
        Objects.requireNonNull(attemptId, "attemptId");
        boolean signalled = workerPool.cancel(attemptId);
        PendingExecution pending = inFlight.get(attemptId);
        if (pending != null && pending.entered.compareAndSet(false, true)) {
            inFlight.remove(attemptId, pending);
            pending.outcomeFuture.complete(ExecutionOutcome.cancelledBeforeStart(attemptId));
        }
        return signalled;
    }

    public int trackedCount() {
        return inFlight.size();
    }

    private ExecutionOutcome executeAttemptLifecycle(
            WorkerTaskHandler.TaskRequest request, WorkerTaskHandler handler) {
        UUID tenantId = request.tenantId();
        UUID runId = request.runId();
        UUID attemptId = request.attemptId();

        if (isLocallyCancelled(attemptId)) {
            return ExecutionOutcome.cancelledBeforeStart(attemptId);
        }

        // 1. Durable admission: acceptAttempt -> startAttempt before invoking handler.
        try {
            repository.acceptAttempt(tenantId, runId, attemptId, request.dispatchId(), request.workerId());
        } catch (WorkflowRuntimeRepository.InvalidTransitionException
                 | WorkflowRuntimeRepository.StaleRuntimeWriteException rejected) {
            return ExecutionOutcome.rejectedBeforeStart(attemptId, rejected);
        }

        if (isLocallyCancelled(attemptId)) {
            return settleWithClearedInterrupt(() -> settleCancellation(request, "cancelled-before-start", false));
        }

        try {
            repository.startAttempt(tenantId, runId, attemptId, request.dispatchId(), request.workerId());
        } catch (WorkflowRuntimeRepository.InvalidTransitionException
                 | WorkflowRuntimeRepository.StaleRuntimeWriteException rejected) {
            return ExecutionOutcome.rejectedBeforeStart(attemptId, rejected);
        }

        // 2. Run handler with a strictly scoped context that hides WorkflowRuntimeRepository.
        ScopedWorkerContext context = new ScopedWorkerContext(request);
        WorkerTaskHandler.TaskResult result;
        try {
            result = handler.execute(request, context);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return settleWithClearedInterrupt(() ->
                    settleCancellation(request, "interrupted", context.hasRecordedSideEffect()));
        } catch (WorkerTaskHandler.TaskCancelledException cancelled) {
            return settleWithClearedInterrupt(() ->
                    settleCancellation(request, cancelled.reason(), context.hasRecordedSideEffect()));
        } catch (WorkerTaskHandler.TaskFailureException failure) {
            boolean uncertain = failure.isSideEffectUncertain() || context.hasRecordedSideEffect();
            return settleWithClearedInterrupt(() ->
                    settleFailure(request, failure.errorCode(), failure, uncertain));
        } catch (Throwable failure) {
            boolean cancelledWhileFailing = isLocallyCancelled(attemptId);
            boolean uncertain = context.hasRecordedSideEffect();
            if (cancelledWhileFailing && !uncertain) {
                return settleWithClearedInterrupt(() ->
                        settleCancellation(request, "cancelled-during-execution", false));
            }
            return settleWithClearedInterrupt(() ->
                    settleFailure(request, failure.getClass().getSimpleName(), failure, uncertain));
        } finally {
            context.deactivate();
        }

        // 3. Handler returned normally: commit completion and notify GraphCoordinator mailbox.
        if (result == null) {
            return settleWithClearedInterrupt(() -> settleFailure(
                    request,
                    "NULL_HANDLER_RESULT",
                    new IllegalStateException("WorkerTaskHandler returned null TaskResult"),
                    context.hasRecordedSideEffect()));
        }

        return settleWithClearedInterrupt(() -> {
            try {
                WorkflowRunLifecycleCoordinator.CompletionDispatch dispatch =
                        lifecycleCoordinator.completeAttemptAndNotify(
                                tenantId, runId, attemptId,
                                result.outputKey(), result.outputJson(),
                                result.exportedFlowVariables());
                return ExecutionOutcome.succeeded(attemptId, dispatch);
            } catch (WorkflowRuntimeRepository.InvalidTransitionException
                     | IllegalStateException completionRejected) {
                // Fail-closed: if the handler returned success with an unclosed side effect or after
                // recording a side effect (e.g. parallel variable conflict), escalate rather than
                // leaving a hanging STARTED Attempt or pretending no side effect occurred.
                return settleFailure(request, "COMPLETE_ATTEMPT_REJECTED",
                        completionRejected, context.hasRecordedSideEffect());
            } catch (RuntimeException settlementError) {
                return ExecutionOutcome.settlementFailed(attemptId, settlementError);
            }
        });
    }

    private ExecutionOutcome settleCancellation(
            WorkerTaskHandler.TaskRequest request, String reason, boolean sideEffectRecorded) {
        try {
            WorkflowRuntimeRepository.AttemptFailureResult cancelled =
                    repository.cancelAttempt(request.tenantId(), request.runId(), request.attemptId(), reason);
            return ExecutionOutcome.settledCancellation(request.attemptId(), cancelled);
        } catch (WorkflowRuntimeRepository.InvalidTransitionException cannotCancelCompletedSideEffect) {
            // Side effect already completed (cannot cancel/erase completed evidence) or Run no longer admits cancel.
            if (sideEffectRecorded) {
                return settleFailure(request, "CANCEL_AFTER_COMPLETED_SIDE_EFFECT",
                        cannotCancelCompletedSideEffect, true);
            }
            return ExecutionOutcome.settlementFailed(request.attemptId(), cannotCancelCompletedSideEffect);
        } catch (RuntimeException settlementError) {
            return ExecutionOutcome.settlementFailed(request.attemptId(), settlementError);
        }
    }

    private ExecutionOutcome settleFailure(
            WorkerTaskHandler.TaskRequest request, String errorCode,
            Throwable cause, boolean sideEffectUncertain) {
        String errorJson = buildErrorJson(errorCode, cause);
        try {
            WorkflowRuntimeRepository.AttemptFailureResult failed = repository.failAttempt(
                    request.tenantId(), request.runId(), request.attemptId(),
                    errorJson, sideEffectUncertain);
            return ExecutionOutcome.settledFailure(request.attemptId(), failed, cause);
        } catch (RuntimeException settlementError) {
            return ExecutionOutcome.settlementFailed(request.attemptId(), settlementError);
        }
    }

    private ExecutionOutcome settleWithClearedInterrupt(java.util.function.Supplier<ExecutionOutcome> action) {
        return callWithClearedInterrupt(action);
    }

    private static <T> T callWithClearedInterrupt(java.util.function.Supplier<T> action) {
        boolean wasInterrupted = Thread.interrupted();
        try {
            return action.get();
        } finally {
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean isLocallyCancelled(UUID attemptId) {
        if (Thread.currentThread().isInterrupted()) return true;
        WorkerExecutionRegistry.Snapshot snap = executionRegistry.snapshot(attemptId);
        return snap != null && snap.cancellationRequested();
    }

    private final class ScopedWorkerContext implements WorkerExecutionContext {
        private final WorkerTaskHandler.TaskRequest request;
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicBoolean sideEffectRecorded = new AtomicBoolean();

        private ScopedWorkerContext(WorkerTaskHandler.TaskRequest request) {
            this.request = request;
        }

        void deactivate() {
            active.set(false);
        }

        boolean hasRecordedSideEffect() {
            return sideEffectRecorded.get();
        }

        private void requireActive() {
            if (!active.get()) {
                throw new IllegalStateException("WorkerExecutionContext is no longer active for Attempt "
                        + request.attemptId());
            }
        }

        @Override public UUID tenantId() { return request.tenantId(); }

        @Override public UUID runId() { return request.runId(); }

        @Override public UUID attemptId() { return request.attemptId(); }

        @Override public String workerId() { return request.workerId(); }

        @Override public String nodeKey() { return request.nodeKey(); }

        @Override
        public boolean recordSideEffectStarted(String sideEffectKey, String factKey, String payloadJson) {
            requireActive();
            boolean recorded = callWithClearedInterrupt(() -> repository.recordSideEffectFact(
                    request.tenantId(), request.runId(), request.attemptId(),
                    request.workerId(), sideEffectKey, factKey, "SIDE_EFFECT_STARTED", payloadJson));
            if (recorded) sideEffectRecorded.set(true);
            return recorded;
        }

        @Override
        public boolean recordSideEffectCompleted(String sideEffectKey, String factKey, String payloadJson) {
            requireActive();
            boolean recorded = callWithClearedInterrupt(() -> repository.recordSideEffectFact(
                    request.tenantId(), request.runId(), request.attemptId(),
                    request.workerId(), sideEffectKey, factKey, "SIDE_EFFECT_COMPLETED", payloadJson));
            if (recorded) sideEffectRecorded.set(true);
            return recorded;
        }

        @Override
        public boolean recordSideEffectUnknown(String sideEffectKey, String factKey, String payloadJson) {
            requireActive();
            boolean recorded = callWithClearedInterrupt(() -> repository.recordSideEffectFact(
                    request.tenantId(), request.runId(), request.attemptId(),
                    request.workerId(), sideEffectKey, factKey, "SIDE_EFFECT_UNKNOWN", payloadJson));
            if (recorded) sideEffectRecorded.set(true);
            return recorded;
        }

        @Override
        public boolean isCancellationRequested() {
            return isLocallyCancelled(request.attemptId());
        }
    }

    private static String buildErrorJson(String errorCode, Throwable cause) {
        String code = (errorCode == null || errorCode.isBlank()) ? "WORKER_TASK_FAILED" : errorCode;
        String exType = cause == null ? "" : cause.getClass().getName();
        String msg = (cause == null || cause.getMessage() == null) ? "" : cause.getMessage();
        return "{\"errorCode\":\"" + jsonEscape(code)
                + "\",\"exception\":\"" + jsonEscape(exType)
                + "\",\"message\":\"" + jsonEscape(msg) + "\"}";
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}
