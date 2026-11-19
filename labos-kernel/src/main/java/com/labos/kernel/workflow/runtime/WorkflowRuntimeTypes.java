package com.labos.kernel.workflow.runtime;

import java.util.UUID;

/** Immutable V1 Runtime identities and persisted state vocabularies. */
public final class WorkflowRuntimeTypes {
    private WorkflowRuntimeTypes() {}

    public enum RunStatus {
        CREATED, STARTING, RUNNING, PAUSE_REQUESTED, PAUSE_DRAINING, PAUSED,
        RESUME_REQUESTED, STOP_REQUESTED, STOP_DRAINING, SUCCEEDED, FAILED,
        STOPPED, MANUAL_REQUIRED, QUARANTINED
    }

    public enum TokenStatus {
        CREATED, READY, RESERVED, IN_FLIGHT, WAITING, CONSUMED, TERMINAL, INVALIDATED
    }

    public enum ActivationStatus {
        CREATED, READY, ACTIVE, SUSPENDED, RETRY_WAIT, COMPLETED, FAILED,
        MANUAL_REQUIRED, UNKNOWN, CANCELLED, QUARANTINED
    }

    public enum AttemptStatus {
        CREATED, DISPATCH_PENDING, DISPATCHED, ACCEPTED, STARTED, SUSPENDED,
        SUCCEEDED, RETRYABLE_FAILED, PERMANENT_FAILED, UNKNOWN, ABANDONED_SAFE
    }

    public enum BranchStatus {
        ACTIVE, WAITING_JOIN, ARRIVED, SKIPPED, FAILED, CANCEL_PENDING,
        CANCELLED, UNKNOWN, QUARANTINED, CLOSED, CLOSED_FAILED
    }

    public enum JoinStatus {
        CREATED, WAITING, RELEASED, CLOSED_FAILED, QUARANTINED
    }

    /** Command intentionally has no UNKNOWN state. */
    public enum CommandStatus {
        CREATED, PREPARED, DISPATCHING, DELIVERY_UNKNOWN, ACKNOWLEDGED,
        RUNNING, CANCEL_REQUESTED, SUCCEEDED, FAILED, REJECTED,
        CANCELLED_CONFIRMED, QUARANTINED
    }

    public enum LeaseStatus {
        REQUESTED, ACQUIRING, ACQUIRED, RELEASE_PENDING, RELEASED,
        EXPIRED_UNVERIFIED, QUARANTINED
    }

    public enum WaitKind { RETRY, EXTERNAL_EVENT, TIMER, MANUAL, JOIN, RESOURCE }
    public enum WaitOwnerType { WAIT_REGISTRATION, RETRY_REGISTRATION, JOIN_ACTIVATION }

    public record TokenWaitOwner(
            TokenStatus status,
            WaitKind waitKind,
            WaitOwnerType ownerType,
            UUID ownerId
    ) {
        public TokenWaitOwner {
            if (status == TokenStatus.WAITING) {
                if (waitKind == null || ownerType == null || ownerId == null) {
                    throw new IllegalArgumentException("WAITING token must reference exactly one wait owner");
                }
            } else if (waitKind != null || ownerType != null || ownerId != null) {
                throw new IllegalArgumentException("Non-WAITING token must not retain a wait owner");
            }
        }
    }

    public record DeviceAttemptCommandBinding(UUID attemptId, UUID commandId, String payloadHash) {
        public DeviceAttemptCommandBinding {
            if (attemptId == null || commandId == null || payloadHash == null || payloadHash.isBlank()) {
                throw new IllegalArgumentException("Device Attempt command binding is incomplete");
            }
        }
    }
}
