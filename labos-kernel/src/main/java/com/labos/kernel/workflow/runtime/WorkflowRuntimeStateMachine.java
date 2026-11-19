package com.labos.kernel.workflow.runtime;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.labos.kernel.workflow.runtime.WorkflowRuntimeTypes.*;

/**
 * Pure V1 state transition guard. It does not perform persistence or side effects.
 * The database transaction remains the authority for committing a transition.
 */
public final class WorkflowRuntimeStateMachine {
    private static final Map<RunStatus, Set<RunStatus>> RUN = new EnumMap<>(RunStatus.class);
    private static final Map<TokenStatus, Set<TokenStatus>> TOKEN = new EnumMap<>(TokenStatus.class);
    private static final Map<ActivationStatus, Set<ActivationStatus>> ACTIVATION = new EnumMap<>(ActivationStatus.class);
    private static final Map<AttemptStatus, Set<AttemptStatus>> ATTEMPT = new EnumMap<>(AttemptStatus.class);
    private static final Map<BranchStatus, Set<BranchStatus>> BRANCH = new EnumMap<>(BranchStatus.class);
    private static final Map<JoinStatus, Set<JoinStatus>> JOIN = new EnumMap<>(JoinStatus.class);
    private static final Map<CommandStatus, Set<CommandStatus>> COMMAND = new EnumMap<>(CommandStatus.class);
    private static final Map<LeaseStatus, Set<LeaseStatus>> LEASE = new EnumMap<>(LeaseStatus.class);

    static {
        allow(RUN, RunStatus.CREATED, RunStatus.STARTING);
        allow(RUN, RunStatus.STARTING, RunStatus.RUNNING, RunStatus.FAILED);
        allow(RUN, RunStatus.RUNNING, RunStatus.PAUSE_REQUESTED, RunStatus.STOP_REQUESTED,
                RunStatus.FAILED, RunStatus.MANUAL_REQUIRED, RunStatus.QUARANTINED);
        allow(RUN, RunStatus.PAUSE_REQUESTED, RunStatus.PAUSE_DRAINING, RunStatus.STOP_REQUESTED,
                RunStatus.MANUAL_REQUIRED, RunStatus.QUARANTINED);
        allow(RUN, RunStatus.PAUSE_DRAINING, RunStatus.PAUSED, RunStatus.STOP_REQUESTED,
                RunStatus.MANUAL_REQUIRED, RunStatus.QUARANTINED);
        allow(RUN, RunStatus.PAUSED, RunStatus.RESUME_REQUESTED, RunStatus.STOP_REQUESTED);
        allow(RUN, RunStatus.RESUME_REQUESTED, RunStatus.RUNNING, RunStatus.PAUSED,
                RunStatus.STOP_REQUESTED, RunStatus.MANUAL_REQUIRED, RunStatus.QUARANTINED);
        allow(RUN, RunStatus.STOP_REQUESTED, RunStatus.STOP_DRAINING,
                RunStatus.MANUAL_REQUIRED, RunStatus.QUARANTINED);
        allow(RUN, RunStatus.STOP_DRAINING, RunStatus.STOPPED, RunStatus.FAILED,
                RunStatus.MANUAL_REQUIRED, RunStatus.QUARANTINED);
        allow(RUN, RunStatus.MANUAL_REQUIRED, RunStatus.QUARANTINED);
        allow(RUN, RunStatus.QUARANTINED);

        allow(TOKEN, TokenStatus.CREATED, TokenStatus.READY, TokenStatus.INVALIDATED);
        allow(TOKEN, TokenStatus.READY, TokenStatus.RESERVED, TokenStatus.TERMINAL, TokenStatus.INVALIDATED);
        allow(TOKEN, TokenStatus.RESERVED, TokenStatus.READY, TokenStatus.IN_FLIGHT, TokenStatus.INVALIDATED);
        allow(TOKEN, TokenStatus.IN_FLIGHT, TokenStatus.WAITING, TokenStatus.CONSUMED,
                TokenStatus.TERMINAL, TokenStatus.INVALIDATED);
        allow(TOKEN, TokenStatus.WAITING, TokenStatus.READY, TokenStatus.CONSUMED,
                TokenStatus.TERMINAL, TokenStatus.INVALIDATED);
        allow(TOKEN, TokenStatus.CONSUMED);
        allow(TOKEN, TokenStatus.TERMINAL);
        allow(TOKEN, TokenStatus.INVALIDATED);

        allow(ACTIVATION, ActivationStatus.CREATED, ActivationStatus.READY, ActivationStatus.CANCELLED);
        allow(ACTIVATION, ActivationStatus.READY, ActivationStatus.ACTIVE, ActivationStatus.CANCELLED);
        allow(ACTIVATION, ActivationStatus.ACTIVE, ActivationStatus.SUSPENDED, ActivationStatus.RETRY_WAIT,
                ActivationStatus.COMPLETED, ActivationStatus.FAILED, ActivationStatus.MANUAL_REQUIRED,
                ActivationStatus.UNKNOWN, ActivationStatus.CANCELLED, ActivationStatus.QUARANTINED);
        allow(ACTIVATION, ActivationStatus.SUSPENDED, ActivationStatus.READY, ActivationStatus.CANCELLED,
                ActivationStatus.MANUAL_REQUIRED, ActivationStatus.UNKNOWN);
        allow(ACTIVATION, ActivationStatus.RETRY_WAIT, ActivationStatus.READY, ActivationStatus.FAILED,
                ActivationStatus.MANUAL_REQUIRED, ActivationStatus.UNKNOWN, ActivationStatus.CANCELLED);
        allow(ACTIVATION, ActivationStatus.UNKNOWN, ActivationStatus.COMPLETED, ActivationStatus.READY,
                ActivationStatus.RETRY_WAIT, ActivationStatus.MANUAL_REQUIRED, ActivationStatus.QUARANTINED);
        allow(ACTIVATION, ActivationStatus.MANUAL_REQUIRED, ActivationStatus.READY,
                ActivationStatus.FAILED, ActivationStatus.QUARANTINED);
        allow(ACTIVATION, ActivationStatus.COMPLETED);
        allow(ACTIVATION, ActivationStatus.FAILED);
        allow(ACTIVATION, ActivationStatus.CANCELLED);
        allow(ACTIVATION, ActivationStatus.QUARANTINED);

        allow(ATTEMPT, AttemptStatus.CREATED, AttemptStatus.DISPATCH_PENDING, AttemptStatus.ABANDONED_SAFE);
        allow(ATTEMPT, AttemptStatus.DISPATCH_PENDING, AttemptStatus.DISPATCHED,
                AttemptStatus.ABANDONED_SAFE, AttemptStatus.UNKNOWN);
        allow(ATTEMPT, AttemptStatus.DISPATCHED, AttemptStatus.ACCEPTED,
                AttemptStatus.ABANDONED_SAFE, AttemptStatus.UNKNOWN);
        allow(ATTEMPT, AttemptStatus.ACCEPTED, AttemptStatus.STARTED,
                AttemptStatus.ABANDONED_SAFE, AttemptStatus.UNKNOWN);
        allow(ATTEMPT, AttemptStatus.STARTED, AttemptStatus.SUSPENDED, AttemptStatus.SUCCEEDED,
                AttemptStatus.RETRYABLE_FAILED, AttemptStatus.PERMANENT_FAILED, AttemptStatus.UNKNOWN);
        allow(ATTEMPT, AttemptStatus.SUSPENDED, AttemptStatus.ABANDONED_SAFE);
        allow(ATTEMPT, AttemptStatus.UNKNOWN, AttemptStatus.SUCCEEDED, AttemptStatus.ABANDONED_SAFE);
        allow(ATTEMPT, AttemptStatus.SUCCEEDED);
        allow(ATTEMPT, AttemptStatus.RETRYABLE_FAILED);
        allow(ATTEMPT, AttemptStatus.PERMANENT_FAILED);
        allow(ATTEMPT, AttemptStatus.ABANDONED_SAFE);

        allow(BRANCH, BranchStatus.ACTIVE, BranchStatus.WAITING_JOIN, BranchStatus.ARRIVED,
                BranchStatus.SKIPPED, BranchStatus.FAILED, BranchStatus.CANCEL_PENDING, BranchStatus.UNKNOWN);
        allow(BRANCH, BranchStatus.WAITING_JOIN, BranchStatus.ARRIVED, BranchStatus.SKIPPED,
                BranchStatus.FAILED, BranchStatus.CANCEL_PENDING, BranchStatus.UNKNOWN);
        allow(BRANCH, BranchStatus.CANCEL_PENDING, BranchStatus.CANCELLED, BranchStatus.UNKNOWN);
        allow(BRANCH, BranchStatus.ARRIVED, BranchStatus.CLOSED);
        allow(BRANCH, BranchStatus.SKIPPED, BranchStatus.CLOSED);
        allow(BRANCH, BranchStatus.FAILED, BranchStatus.CLOSED_FAILED);
        allow(BRANCH, BranchStatus.CANCELLED, BranchStatus.CLOSED_FAILED);
        allow(BRANCH, BranchStatus.UNKNOWN, BranchStatus.QUARANTINED);
        allow(BRANCH, BranchStatus.QUARANTINED);

        allow(JOIN, JoinStatus.CREATED, JoinStatus.WAITING);
        allow(JOIN, JoinStatus.WAITING, JoinStatus.RELEASED, JoinStatus.CLOSED_FAILED, JoinStatus.QUARANTINED);
        allow(JOIN, JoinStatus.RELEASED);
        allow(JOIN, JoinStatus.CLOSED_FAILED);
        allow(JOIN, JoinStatus.QUARANTINED);

        allow(COMMAND, CommandStatus.CREATED, CommandStatus.PREPARED, CommandStatus.REJECTED);
        allow(COMMAND, CommandStatus.PREPARED, CommandStatus.DISPATCHING, CommandStatus.REJECTED);
        allow(COMMAND, CommandStatus.DISPATCHING, CommandStatus.DELIVERY_UNKNOWN,
                CommandStatus.ACKNOWLEDGED, CommandStatus.REJECTED);
        allow(COMMAND, CommandStatus.DELIVERY_UNKNOWN, CommandStatus.ACKNOWLEDGED,
                CommandStatus.RUNNING, CommandStatus.SUCCEEDED, CommandStatus.FAILED,
                CommandStatus.REJECTED, CommandStatus.QUARANTINED);
        allow(COMMAND, CommandStatus.ACKNOWLEDGED, CommandStatus.RUNNING, CommandStatus.SUCCEEDED,
                CommandStatus.FAILED, CommandStatus.CANCEL_REQUESTED, CommandStatus.QUARANTINED);
        allow(COMMAND, CommandStatus.RUNNING, CommandStatus.SUCCEEDED, CommandStatus.FAILED,
                CommandStatus.CANCEL_REQUESTED, CommandStatus.QUARANTINED);
        allow(COMMAND, CommandStatus.CANCEL_REQUESTED, CommandStatus.CANCELLED_CONFIRMED,
                CommandStatus.SUCCEEDED, CommandStatus.FAILED, CommandStatus.QUARANTINED);
        allow(COMMAND, CommandStatus.SUCCEEDED);
        allow(COMMAND, CommandStatus.FAILED);
        allow(COMMAND, CommandStatus.REJECTED);
        allow(COMMAND, CommandStatus.CANCELLED_CONFIRMED);
        allow(COMMAND, CommandStatus.QUARANTINED);

        allow(LEASE, LeaseStatus.REQUESTED, LeaseStatus.ACQUIRING, LeaseStatus.RELEASED, LeaseStatus.QUARANTINED);
        allow(LEASE, LeaseStatus.ACQUIRING, LeaseStatus.ACQUIRED, LeaseStatus.RELEASED, LeaseStatus.QUARANTINED);
        allow(LEASE, LeaseStatus.ACQUIRED, LeaseStatus.RELEASE_PENDING,
                LeaseStatus.EXPIRED_UNVERIFIED, LeaseStatus.QUARANTINED);
        allow(LEASE, LeaseStatus.RELEASE_PENDING, LeaseStatus.RELEASED,
                LeaseStatus.EXPIRED_UNVERIFIED, LeaseStatus.QUARANTINED);
        allow(LEASE, LeaseStatus.EXPIRED_UNVERIFIED, LeaseStatus.RELEASED, LeaseStatus.QUARANTINED);
        allow(LEASE, LeaseStatus.QUARANTINED);
        allow(LEASE, LeaseStatus.RELEASED);
    }

    private static <S extends Enum<S>> void allow(Map<S, Set<S>> map, S from, S... to) {
        map.put(from, to.length == 0 ? EnumSet.noneOf(from.getDeclaringClass())
                : EnumSet.of(to[0], to));
    }

    public static boolean canTransition(RunStatus from, RunStatus to) { return allowed(RUN, from, to); }
    public static boolean canTransition(TokenStatus from, TokenStatus to) { return allowed(TOKEN, from, to); }
    public static boolean canTransition(ActivationStatus from, ActivationStatus to) { return allowed(ACTIVATION, from, to); }
    public static boolean canTransition(AttemptStatus from, AttemptStatus to) { return allowed(ATTEMPT, from, to); }
    public static boolean canTransition(BranchStatus from, BranchStatus to) { return allowed(BRANCH, from, to); }
    public static boolean canTransition(JoinStatus from, JoinStatus to) { return allowed(JOIN, from, to); }
    public static boolean canTransition(CommandStatus from, CommandStatus to) { return allowed(COMMAND, from, to); }
    public static boolean canTransition(LeaseStatus from, LeaseStatus to) { return allowed(LEASE, from, to); }

    /** Quarantine exits are explicit evidence-bearing decisions, never ordinary transitions. */
    public static boolean canClearQuarantinedLease(boolean authoritativeEvidence, boolean authorized) {
        return authoritativeEvidence && authorized;
    }

    public static boolean canReconcileQuarantinedJoin(
            JoinStatus target, boolean authoritativeEvidence, boolean authorized) {
        return authoritativeEvidence && authorized
                && (target == JoinStatus.WAITING || target == JoinStatus.CLOSED_FAILED);
    }

    public static void requireTransition(RunStatus from, RunStatus to) { require(canTransition(from, to), from, to); }
    public static void requireTransition(TokenStatus from, TokenStatus to) { require(canTransition(from, to), from, to); }
    public static void requireTransition(ActivationStatus from, ActivationStatus to) { require(canTransition(from, to), from, to); }
    public static void requireTransition(AttemptStatus from, AttemptStatus to) { require(canTransition(from, to), from, to); }
    public static void requireTransition(BranchStatus from, BranchStatus to) { require(canTransition(from, to), from, to); }
    public static void requireTransition(JoinStatus from, JoinStatus to) { require(canTransition(from, to), from, to); }
    public static void requireTransition(CommandStatus from, CommandStatus to) { require(canTransition(from, to), from, to); }
    public static void requireTransition(LeaseStatus from, LeaseStatus to) { require(canTransition(from, to), from, to); }

    private static <S extends Enum<S>> boolean allowed(Map<S, Set<S>> map, S from, S to) {
        return from != null && to != null && map.getOrDefault(from, Set.of()).contains(to);
    }

    private static void require(boolean allowed, Object from, Object to) {
        if (!allowed) throw new IllegalStateException("Illegal Workflow Runtime transition: " + from + " -> " + to);
    }
}
