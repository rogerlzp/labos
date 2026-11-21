package com.labos.kernel.workflow.runtime.device.contract;

/** Sidecar synchronous acknowledgement statuses returned in {@code COMMAND_RECEIPT}. */
public enum ReceiptStatus {
    ACCEPTED,
    DUPLICATE_IN_FLIGHT,
    COMPLETED_IMMEDIATELY,
    FAILED_IMMEDIATELY
}
