package com.labos.kernel.workflow.runtime.device.contract;

/** Sidecar read-only reconciliation outcomes returned in {@code COMMAND_QUERY_RESULT}. */
public enum ReconciliationStatus {
    STILL_RUNNING,
    CONFIRMED_SUCCEEDED,
    CONFIRMED_FAILED,
    CONFIRMED_NOT_EXECUTED,
    CONFIRMED_CANCELLED,
    INDETERMINATE
}
