package com.labos.kernel.workflow.runtime.device.contract;

/** Physical dispatch boundary attached to {@link ReceiptStatus#FAILED_IMMEDIATELY}. */
public enum FailureBoundary {
    /** Rejected cleanly before transmitting or actuating physical hardware. */
    PRE_EXECUTION,
    /** Failure occurred after potential hardware contact; requires UNKNOWN + quarantine. */
    POST_EXECUTION_UNKNOWN
}
