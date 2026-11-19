package com.labos.kernel.workflow.runtime;

import java.util.Map;
import java.util.UUID;

/**
 * Transaction port for T01-T23. Implementations must persist the complete
 * transition, Runtime Event, Outbox and Inbox outcome in one DB transaction.
 * No Handler or Worker may update Runtime projection tables directly.
 */
public interface WorkflowRuntimeTransactionPort {
    record Request(
            UUID runId,
            String requestId,
            String messageId,
            String payloadHash,
            long expectedRunCommitVersion,
            long coordinatorEpoch,
            Map<String, Object> payload
    ) {
        public Request {
            if (runId == null) throw new IllegalArgumentException("runId required");
            if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("requestId required");
            if (payloadHash == null || !payloadHash.matches("[0-9a-fA-F]{64}"))
                throw new IllegalArgumentException("payloadHash must be a 64-character SHA-256 hex string");
            if (expectedRunCommitVersion < 0 || coordinatorEpoch < 0) throw new IllegalArgumentException("negative version/epoch");
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    record Result(
            java.util.UUID runId,
            String outcome,
            long runCommitVersion,
            long firstEventSeq,
            long lastEventSeq,
            boolean replayed
    ) {}

    Result createRun(Request request);                       // T01
    Result startRun(Request request);                        // T02
    Result reserveAndCommitDispatch(Request request);        // T03
    Result workerDispatchStarted(Request request);            // T03 Dispatch Outbox send boundary
    Result workerAccepted(Request request);                  // T04a
    Result workerStarted(Request request);                   // T04b
    Result completeNode(Request request);                    // T05
    Result failNode(Request request);                        // T06
    Result suspendActivation(Request request);               // T07
    Result resolveWait(Request request);                     // T08
    Result expandSplit(Request request);                     // T09
    Result arriveBranchAndReleaseJoin(Request request);      // T10
    Result advanceLoop(Request request);                     // T11
    Result createDeviceCommand(Request request);              // T12
    Result dispatchDeviceCommand(Request request);            // T13
    Result consumeDeviceEvent(Request request);               // T14
    Result requestPauseOrStop(Request request);               // T15
    Result startDrain(Request request);                       // T16
    Result reconcileReservations(Request request);            // T17
    Result advanceSafePoint(Request request);                 // T18
    Result completeDrain(Request request);                     // T19
    Result createCheckpoint(Request request);                  // T20
    Result reconcileUnknown(Request request);                  // T21
    Result recordManualResolution(Request request);            // T22
    Result clearQuarantine(Request request);                   // T23
}
