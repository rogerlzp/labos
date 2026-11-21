package com.labos.kernel.workflow.runtime.coordinator;

/**
 * G5 Wait outcome vocabulary shared between the Repository transaction layer (AI-1)
 * and the Coordinator / DispatchLoop scheduling layer (AI-3).
 */
public final class WaitOutcomes {
    private WaitOutcomes() {}

    /** Registration, completion, or synchronized Inbox consumption mutated durable state. */
    public static final String COMPLETED = "COMPLETED";

    /** Wait / Token / Inbox had already reached the requested completed state (idempotent replay). */
    public static final String ALREADY_COMPLETED = "ALREADY_COMPLETED";

    /** Run is paused or gate is not open yet; operation deferred cleanly. */
    public static final String DEFERRED_RUN_NOT_OPEN = "DEFERRED_RUN_NOT_OPEN";

    /** Expected state or optimistic version no longer matches (lost a race / not yet due). */
    public static final String STALE_VERSION = "STALE_VERSION";

    /** Referenced Run, Wait, Token, or Inbox record does not exist. */
    public static final String NOT_FOUND = "NOT_FOUND";

    /** Permanently rejected (domain/protocol error or terminal Run); never silently retried. */
    public static final String REJECTED = "REJECTED";

    /** Duplicate Inbox identity arrived with conflicting payload/type/run (G4-D). */
    public static final String PROTOCOL_VIOLATION = "PROTOCOL_VIOLATION";

    /** Timer arbitration detected an eligible pending Inbox received strictly before deadline_at. */
    public static final String WAIT_FOR_INBOX = "WAIT_FOR_INBOX";

    /** Wait completion outcome when satisfied by an external signal. */
    public static final String OUTCOME_SATISFIED = "SATISFIED";

    /** Wait completion outcome when expired by durable database-time timer. */
    public static final String OUTCOME_EXPIRED = "EXPIRED";
}
