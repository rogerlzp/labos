package com.labos.kernel.workflow.runtime.device.contract;

import java.util.Set;

/**
 * Frozen Contract v1.x error codes specified in {@code DEVICE_GATEWAY_PROTOCOL.md §3.3}.
 */
public final class ContractV1ErrorCodes {

    private ContractV1ErrorCodes() {}

    public static final String ERR_WIRE_UNSUPPORTED_VERSION = "ERR_WIRE_UNSUPPORTED_VERSION";
    public static final String ERR_WIRE_MALFORMED_FRAME = "ERR_WIRE_MALFORMED_FRAME";
    public static final String ERR_WIRE_FORBIDDEN_EVIDENCE_SOURCE = "ERR_WIRE_FORBIDDEN_EVIDENCE_SOURCE";
    public static final String ERR_WIRE_SPOOFED_DEVICE = "ERR_WIRE_SPOOFED_DEVICE";

    public static final String ERR_SESSION_IDENTITY_MISMATCH = "ERR_SESSION_IDENTITY_MISMATCH";
    public static final String ERR_SESSION_DEVICE_CONFLICT = "ERR_SESSION_DEVICE_CONFLICT";
    public static final String ERR_SESSION_STALE = "ERR_SESSION_STALE";

    public static final String ERR_CAPABILITY_MISSING_OP = "ERR_CAPABILITY_MISSING_OP";
    public static final String ERR_CAPABILITY_CANCEL_UNSUPPORTED = "ERR_CAPABILITY_CANCEL_UNSUPPORTED";

    public static final String ERR_SAFETY_IMPACT_UNDECLARED = "ERR_SAFETY_IMPACT_UNDECLARED";
    public static final String ERR_SAFETY_LEASE_BUSY = "ERR_SAFETY_LEASE_BUSY";
    public static final String ERR_SAFETY_QUARANTINED = "ERR_SAFETY_QUARANTINED";
    public static final String ERR_SAFETY_PRECONDITION_FAILED = "ERR_SAFETY_PRECONDITION_FAILED";

    public static final String ERR_EVENT_UNROUTABLE = "ERR_EVENT_UNROUTABLE";
    public static final String ERR_EVENT_RUN_TERMINAL = "ERR_EVENT_RUN_TERMINAL";

    public static final String ERR_DEVICE_REJECTED_PRE_EXECUTION = "ERR_DEVICE_REJECTED_PRE_EXECUTION";
    public static final String ERR_DEVICE_EXECUTION_FAILED = "ERR_DEVICE_EXECUTION_FAILED";

    public static final String ERR_RECONCILE_DB_TRANSIENT = "ERR_RECONCILE_DB_TRANSIENT";
    public static final String ERR_RECONCILE_INDETERMINATE = "ERR_RECONCILE_INDETERMINATE";

    public static final Set<String> ALL_CODES = Set.of(
            ERR_WIRE_UNSUPPORTED_VERSION,
            ERR_WIRE_MALFORMED_FRAME,
            ERR_WIRE_FORBIDDEN_EVIDENCE_SOURCE,
            ERR_WIRE_SPOOFED_DEVICE,
            ERR_SESSION_IDENTITY_MISMATCH,
            ERR_SESSION_DEVICE_CONFLICT,
            ERR_SESSION_STALE,
            ERR_CAPABILITY_MISSING_OP,
            ERR_CAPABILITY_CANCEL_UNSUPPORTED,
            ERR_SAFETY_IMPACT_UNDECLARED,
            ERR_SAFETY_LEASE_BUSY,
            ERR_SAFETY_QUARANTINED,
            ERR_SAFETY_PRECONDITION_FAILED,
            ERR_EVENT_UNROUTABLE,
            ERR_EVENT_RUN_TERMINAL,
            ERR_DEVICE_REJECTED_PRE_EXECUTION,
            ERR_DEVICE_EXECUTION_FAILED,
            ERR_RECONCILE_DB_TRANSIENT,
            ERR_RECONCILE_INDETERMINATE);
}
