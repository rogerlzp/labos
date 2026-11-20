package com.labos.kernel.workflow.runtime.worker;

import com.labos.kernel.workflow.runtime.coordinator.WorkflowRunLifecycleCoordinator;
import com.labos.kernel.workflow.runtime.persistence.WorkflowRuntimeRepository;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Application service owning the single-Attempt Worker execution lifecycle on the
 * single embedded Runtime Host.
 *
 * <p>Responsibilities and strict boundaries:
 * <ul>
 *   <li>Runs synchronous handlers on {@link WorkerPool} while keeping {@link WorkflowRuntimeRepository}
 *       out of handler reach (handlers receive only an owning-thread-bound {@link WorkerExecutionContext}).</li>
 *   <li>Enforces durable {@code acceptAttemptForExecution -> startAttempt} admission before invoking any
 *       handler; if the Run closes its dispatch gate before or between accept and start, durably converges
 *       the pre-start Attempt via {@code abortPreStartAttemptOnGateClosed} so Drain never stalls on an
 *       orphaned {@code DISPATCHED} or {@code ACCEPTED} Attempt.</li>
 *   <li>Durably settles queued cancellations via {@code cancelAttempt} BEFORE completing the outcome
 *       future; if DB settlement fails, reports {@link OutcomeStatus#SETTLEMENT_FAILED} rather than
 *       falsely claiming cancellation succeeded.</li>
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

        private static ExecutionOutcome rejectedBeforeStart(UUID attemptId, String runOutcome, Throwable error) {
            return new ExecutionOutcome(attemptId, OutcomeStatus.REJECTED_BEFORE_START, runOutcome, null,
                    false, CompletableFuture.completedFuture(null), error);
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
        final WorkerTaskHandler.TaskRequest request;
        final CompletableFuture<ExecutionOutcome> outcomeFuture = new CompletableFuture<>();
        final AtomicBoolean entered = new AtomicBoolean();

        PendingExecution(WorkerTaskHandler.TaskRequest request) {
            this.request = request;
        }
    }

    private final WorkflowRuntimeRepository repository;
    private final WorkflowRunLifecycleCoordinator lifecycleCoordinator;
    private final WorkerPool workerPool;
    private final WorkerExecutionRegistry executionRegistry;
    private final Runnable afterAcceptBeforeStartHook;
    private final ConcurrentMap<UUID, PendingExecution> inFlight = new ConcurrentHashMap<>();

    public WorkerExecutionService(
            WorkflowRuntimeRepository repository,
            WorkflowRunLifecycleCoordinator lifecycleCoordinator,
            WorkerPool workerPool,
            WorkerExecutionRegistry executionRegistry) {
        this(repository, lifecycleCoordinator, workerPool, executionRegistry, () -> {});
    }

    /** Package-private seam for deterministic tests exercising gate closure between accept and start. */
    WorkerExecutionService(
            WorkflowRuntimeRepository repository,
            WorkflowRunLifecycleCoordinator lifecycleCoordinator,
            WorkerPool workerPool,
            WorkerExecutionRegistry executionRegistry,
            Runnable afterAcceptBeforeStartHook) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.lifecycleCoordinator = Objects.requireNonNull(lifecycleCoordinator, "lifecycleCoordinator");
        this.workerPool = Objects.requireNonNull(workerPool, "workerPool");
        this.executionRegistry = Objects.requireNonNull(executionRegistry, "executionRegistry");
        this.afterAcceptBeforeStartHook = Objects.requireNonNull(
                afterAcceptBeforeStartHook, "afterAcceptBeforeStartHook");
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

        PendingExecution pending = new PendingExecution(request);
        if (inFlight.putIfAbsent(request.attemptId(), pending) != null) {
            throw new IllegalStateException("Attempt already submitted to WorkerExecutionService: "
                    + request.attemptId());
        }
        AtomicBoolean workerOwnsCompletion = new AtomicBoolean();
        AtomicReference<ExecutionOutcome> outcomeRef = new AtomicReference<>();
        Runnable publishAfterPoolRelease = () -> {
            if (!workerOwnsCompletion.get()) return;
            inFlight.remove(request.attemptId(), pending);
            ExecutionOutcome outcome = outcomeRef.get();
            if (outcome != null) {
                pending.outcomeFuture.complete(outcome);
            } else if (!pending.outcomeFuture.isDone()) {
                pending.outcomeFuture.complete(ExecutionOutcome.settlementFailed(
                        request.attemptId(),
                        new IllegalStateException("Worker execution exited without outcome")));
            }
        };
        try {
            workerPool.submit(request.runId(), request.attemptId(), request.workerId(), () -> {
                if (!pending.entered.compareAndSet(false, true)) {
                    // requestCancel won the queued->running race and owns durable cancellation settlement.
                    return null;
                }
                workerOwnsCompletion.set(true);
                try {
                    ExecutionOutcome outcome = executeAttemptLifecycle(request, handler);
                    outcomeRef.set(outcome);
                    return outcome;
                } catch (Throwable unexpected) {
                    ExecutionOutcome failed = ExecutionOutcome.settlementFailed(request.attemptId(), unexpected);
                    outcomeRef.set(failed);
                    if (unexpected instanceof Error err) throw err;
                    return failed;
                }
            }, publishAfterPoolRelease);
            return pending.outcomeFuture;
        } catch (RuntimeException | Error submissionFailure) {
            inFlight.remove(request.attemptId(), pending);
            throw submissionFailure;
        }
    }

    /**
     * Request cancellation for a submitted Attempt.
     *
     * <p>If the Attempt has not yet entered the Worker lifecycle (still queued in {@link WorkerPool}),
     * this method durably settles {@code cancelAttempt} in PostgreSQL BEFORE completing the outcome
     * future and returning; if DB settlement fails, the future completes with
     * {@link OutcomeStatus#SETTLEMENT_FAILED} and this method returns {@code false}.
     * If the Worker thread has already entered execution, local cancellation is signalled and the
     * Worker thread performs durable {@code CANCELLED} / {@code MANUAL_REQUIRED} settlement before
     * completing the outcome future.
     */
    public boolean requestCancel(UUID attemptId) {
        Objects.requireNonNull(attemptId, "attemptId");
        boolean signalled = workerPool.cancel(attemptId);
        PendingExecution pending = inFlight.get(attemptId);
        if (pending != null && pending.entered.compareAndSet(false, true)) {
            ExecutionOutcome settled;
            try {
                settled = settleWithClearedInterrupt(() ->
                        settleCancellation(pending.request, "cancelled-while-queued", false));
            } finally {
                inFlight.remove(attemptId, pending);
            }
            pending.outcomeFuture.complete(settled);
            return settled.status() == OutcomeStatus.CANCELLED
                    || settled.status() == OutcomeStatus.MANUAL_REQUIRED;
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
            return settleWithClearedInterrupt(() ->
                    settleCancellation(request, "cancelled-before-accept", false));
        }

        // 1. Durable admission: acceptAttemptForExecution -> startAttempt before invoking handler.
        try {
            repository.acceptAttemptForExecution(
                    tenantId, runId, attemptId, request.dispatchId(), request.workerId(),
                    request.nodeKey(), request.sideEffectClass());
        } catch (WorkflowRuntimeRepository.InvalidTransitionException gateClosed) {
            return settleWithClearedInterrupt(() ->
                    settlePreStartGateRejection(request, "gate-closed-before-accept", gateClosed));
        } catch (WorkflowRuntimeRepository.StaleRuntimeWriteException staleOrMismatch) {
            return ExecutionOutcome.rejectedBeforeStart(attemptId, null, staleOrMismatch);
        }

        afterAcceptBeforeStartHook.run();

        if (isLocallyCancelled(attemptId)) {
            return settleWithClearedInterrupt(() ->
                    settleCancellation(request, "cancelled-before-start", false));
        }

        try {
            repository.startAttempt(tenantId, runId, attemptId, request.dispatchId(), request.workerId());
        } catch (WorkflowRuntimeRepository.InvalidTransitionException gateClosed) {
            return settleWithClearedInterrupt(() ->
                    settlePreStartGateRejection(request, "gate-closed-before-start", gateClosed));
        } catch (WorkflowRuntimeRepository.StaleRuntimeWriteException stale) {
            return ExecutionOutcome.rejectedBeforeStart(attemptId, null, stale);
        }

        // 2. Run synchronous handler with an owning-thread-bound context hiding WorkflowRuntimeRepository.
        ScopedWorkerContext context = new ScopedWorkerContext(request, Thread.currentThread());
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

    private ExecutionOutcome settlePreStartGateRejection(
            WorkerTaskHandler.TaskRequest request, String reason,
            WorkflowRuntimeRepository.InvalidTransitionException gateRejection) {
        try {
            WorkflowRuntimeRepository.AttemptFailureResult aborted =
                    repository.abortPreStartAttemptOnGateClosed(
                            request.tenantId(), request.runId(), request.attemptId(), reason);
            return ExecutionOutcome.rejectedBeforeStart(request.attemptId(), aborted.outcome(), gateRejection);
        } catch (WorkflowRuntimeRepository.InvalidTransitionException
                 | WorkflowRuntimeRepository.StaleRuntimeWriteException alreadyTerminalOrNotDrain) {
            // Run was already terminal (e.g. FAILED/STOPPED/PAUSED/MANUAL_REQUIRED) or Attempt was
            // already transitioned concurrently; never overwrite terminal state.
            return ExecutionOutcome.rejectedBeforeStart(request.attemptId(), null, gateRejection);
        } catch (RuntimeException dbFailure) {
            return ExecutionOutcome.settlementFailed(request.attemptId(), dbFailure);
        }
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

    /**
     * Synchronous, owning-thread-bound context. All side-effect recordings and {@link #deactivate()}
     * serialize on {@code lock} and enforce {@code Thread.currentThread() == owningThread}, making
     * it impossible for a background thread to race past deactivation into completion/failure settlement.
     */
    private final class ScopedWorkerContext implements WorkerExecutionContext {
        private final WorkerTaskHandler.TaskRequest request;
        private final Thread owningThread;
        private final Object lock = new Object();
        private boolean active = true;
        private final AtomicBoolean sideEffectRecorded = new AtomicBoolean();

        private ScopedWorkerContext(WorkerTaskHandler.TaskRequest request, Thread owningThread) {
            this.request = request;
            this.owningThread = owningThread;
        }

        void deactivate() {
            synchronized (lock) {
                active = false;
            }
        }

        boolean hasRecordedSideEffect() {
            return sideEffectRecorded.get();
        }

        private void requireOwningThreadAndActiveLocked() {
            if (Thread.currentThread() != owningThread) {
                throw new IllegalStateException(
                        "WorkerExecutionContext is synchronous and bound to the owning Worker thread");
            }
            if (!active) {
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
            synchronized (lock) {
                requireOwningThreadAndActiveLocked();
                boolean recorded = callWithClearedInterrupt(() -> repository.recordSideEffectFact(
                        request.tenantId(), request.runId(), request.attemptId(),
                        request.workerId(), sideEffectKey, factKey, "SIDE_EFFECT_STARTED", payloadJson));
                if (recorded) sideEffectRecorded.set(true);
                return recorded;
            }
        }

        @Override
        public boolean recordSideEffectCompleted(String sideEffectKey, String factKey, String payloadJson) {
            synchronized (lock) {
                requireOwningThreadAndActiveLocked();
                boolean recorded = callWithClearedInterrupt(() -> repository.recordSideEffectFact(
                        request.tenantId(), request.runId(), request.attemptId(),
                        request.workerId(), sideEffectKey, factKey, "SIDE_EFFECT_COMPLETED", payloadJson));
                if (recorded) sideEffectRecorded.set(true);
                return recorded;
            }
        }

        @Override
        public boolean recordSideEffectUnknown(String sideEffectKey, String factKey, String payloadJson) {
            synchronized (lock) {
                requireOwningThreadAndActiveLocked();
                boolean recorded = callWithClearedInterrupt(() -> repository.recordSideEffectFact(
                        request.tenantId(), request.runId(), request.attemptId(),
                        request.workerId(), sideEffectKey, factKey, "SIDE_EFFECT_UNKNOWN", payloadJson));
                if (recorded) sideEffectRecorded.set(true);
                return recorded;
            }
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
