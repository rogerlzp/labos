package com.labos.kernel.workflow.runtime.worker;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Application-layer SPI for executing a single Workflow Runtime Attempt on a local Worker thread.
 *
 * <p>Implementations handle pure-logic or mock side-effect work and interact with durable
 * side-effect facts exclusively through {@link WorkerExecutionContext}.
 */
@FunctionalInterface
public interface WorkerTaskHandler {
    TaskResult execute(TaskRequest request, WorkerExecutionContext context) throws Exception;

    record TaskRequest(
            UUID tenantId,
            UUID runId,
            UUID attemptId,
            UUID dispatchId,
            String workerId,
            String nodeKey,
            String sideEffectClass,
            String payloadJson) {
        public TaskRequest {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(attemptId, "attemptId");
            Objects.requireNonNull(dispatchId, "dispatchId");
            if (workerId == null || workerId.isBlank())
                throw new IllegalArgumentException("workerId is required");
            if (nodeKey == null || nodeKey.isBlank())
                throw new IllegalArgumentException("nodeKey is required");
            if (sideEffectClass == null || sideEffectClass.isBlank())
                throw new IllegalArgumentException("sideEffectClass is required");
            payloadJson = (payloadJson == null || payloadJson.isBlank()) ? "{}" : payloadJson;
        }
    }

    record TaskResult(String outputKey, String outputJson, Map<String, String> exportedFlowVariables) {
        public TaskResult {
            if (outputKey == null || outputKey.isBlank())
                throw new IllegalArgumentException("outputKey is required");
            outputJson = (outputJson == null || outputJson.isBlank()) ? "{}" : outputJson;
            exportedFlowVariables = (exportedFlowVariables == null || exportedFlowVariables.isEmpty())
                    ? Map.of()
                    : Map.copyOf(exportedFlowVariables);
        }

        public static TaskResult of(String outputKey, String outputJson) {
            return new TaskResult(outputKey, outputJson, Map.of());
        }

        public static TaskResult of(String outputKey, String outputJson, Map<String, String> exportedFlowVariables) {
            return new TaskResult(outputKey, outputJson, exportedFlowVariables);
        }
    }

    /**
     * Explicit task failure distinguishing deterministic (pre-side-effect) errors from
     * uncertain side-effect failures that must escalate fail-closed to {@code MANUAL_REQUIRED}.
     */
    final class TaskFailureException extends RuntimeException {
        private final String errorCode;
        private final boolean sideEffectUncertain;

        public TaskFailureException(String errorCode, String message, boolean sideEffectUncertain, Throwable cause) {
            super(message, cause);
            this.errorCode = (errorCode == null || errorCode.isBlank()) ? "WORKER_TASK_FAILED" : errorCode;
            this.sideEffectUncertain = sideEffectUncertain;
        }

        public static TaskFailureException deterministic(String errorCode, String message) {
            return new TaskFailureException(errorCode, message, false, null);
        }

        public static TaskFailureException uncertain(String errorCode, String message, Throwable cause) {
            return new TaskFailureException(errorCode, message, true, cause);
        }

        public String errorCode() { return errorCode; }

        public boolean isSideEffectUncertain() { return sideEffectUncertain; }
    }

    /**
     * Explicit cancellation exception thrown by a cooperative handler after observing
     * {@link WorkerExecutionContext#isCancellationRequested()}.
     */
    final class TaskCancelledException extends RuntimeException {
        private final String reason;

        public TaskCancelledException(String reason) {
            super(reason);
            this.reason = (reason == null || reason.isBlank()) ? "worker-cancelled" : reason;
        }

        public String reason() { return reason; }
    }
}
