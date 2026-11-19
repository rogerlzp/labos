package com.labos.kernel.workflow.runtime;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.labos.kernel.workflow.runtime.WorkflowRuntimeTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowRuntimeStateMachineTest {

    @Test
    void activationBecomesActiveOnlyAfterDispatchCommit() {
        // The only repository event allowed to invoke this transition is DispatchCommitted.
        assertTrue(WorkflowRuntimeStateMachine.canTransition(ActivationStatus.READY, ActivationStatus.ACTIVE));
        assertFalse(WorkflowRuntimeStateMachine.canTransition(ActivationStatus.CREATED, ActivationStatus.ACTIVE));
    }

    @Test
    void workerMustBeAcceptedBeforeStarted() {
        assertFalse(WorkflowRuntimeStateMachine.canTransition(AttemptStatus.DISPATCHED, AttemptStatus.STARTED));
        assertTrue(WorkflowRuntimeStateMachine.canTransition(AttemptStatus.DISPATCHED, AttemptStatus.ACCEPTED));
        assertTrue(WorkflowRuntimeStateMachine.canTransition(AttemptStatus.ACCEPTED, AttemptStatus.STARTED));
    }

    @Test
    void branchAndJoinHaveClosedTerminalTransitions() {
        assertTrue(WorkflowRuntimeStateMachine.canTransition(BranchStatus.ARRIVED, BranchStatus.CLOSED));
        assertTrue(WorkflowRuntimeStateMachine.canTransition(BranchStatus.SKIPPED, BranchStatus.CLOSED));
        assertTrue(WorkflowRuntimeStateMachine.canTransition(BranchStatus.FAILED, BranchStatus.CLOSED_FAILED));
        assertTrue(WorkflowRuntimeStateMachine.canTransition(JoinStatus.WAITING, JoinStatus.RELEASED));
        assertTrue(WorkflowRuntimeStateMachine.canTransition(JoinStatus.WAITING, JoinStatus.CLOSED_FAILED));
        assertFalse(WorkflowRuntimeStateMachine.canTransition(JoinStatus.RELEASED, JoinStatus.WAITING));
    }

    @Test
    void commandDoesNotHaveUnknownStateAndQuarantineBlocks() {
        assertFalse(java.util.Arrays.stream(CommandStatus.values()).anyMatch(s -> s.name().equals("UNKNOWN")));
        assertTrue(WorkflowRuntimeStateMachine.canTransition(CommandStatus.DISPATCHING, CommandStatus.DELIVERY_UNKNOWN));
        assertTrue(WorkflowRuntimeStateMachine.canTransition(CommandStatus.RUNNING, CommandStatus.QUARANTINED));
        assertFalse(WorkflowRuntimeStateMachine.canTransition(CommandStatus.QUARANTINED, CommandStatus.SUCCEEDED));
    }

    @Test
    void waitingTokenRequiresExactlyOneOwner() {
        assertDoesNotThrow(() -> new TokenWaitOwner(TokenStatus.WAITING, WaitKind.RETRY,
                WaitOwnerType.RETRY_REGISTRATION, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class,
                () -> new TokenWaitOwner(TokenStatus.WAITING, WaitKind.RETRY, null, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class,
                () -> new TokenWaitOwner(TokenStatus.READY, null, WaitOwnerType.WAIT_REGISTRATION, UUID.randomUUID()));
    }

    @Test
    void leaseQuarantineNeedsEvidenceAndAuthorization() {
        assertFalse(WorkflowRuntimeStateMachine.canClearQuarantinedLease(false, true));
        assertFalse(WorkflowRuntimeStateMachine.canClearQuarantinedLease(true, false));
        assertTrue(WorkflowRuntimeStateMachine.canClearQuarantinedLease(true, true));
        assertFalse(WorkflowRuntimeStateMachine.canTransition(LeaseStatus.QUARANTINED, LeaseStatus.RELEASED));
        assertTrue(WorkflowRuntimeStateMachine.canReconcileQuarantinedJoin(JoinStatus.CLOSED_FAILED, true, true));
        assertFalse(WorkflowRuntimeStateMachine.canReconcileQuarantinedJoin(JoinStatus.RELEASED, true, true));
        assertFalse(WorkflowRuntimeStateMachine.canReconcileQuarantinedJoin(JoinStatus.WAITING, true, false));
    }

    @Test
    void deviceAttemptCommandBindingIsOneToOneAndImmutable() {
        assertDoesNotThrow(() -> new DeviceAttemptCommandBinding(UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> new DeviceAttemptCommandBinding(UUID.randomUUID(), null, "a".repeat(64)));
    }
}
