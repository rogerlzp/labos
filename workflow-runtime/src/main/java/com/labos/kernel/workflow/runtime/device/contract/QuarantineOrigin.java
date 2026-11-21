package com.labos.kernel.workflow.runtime.device.contract;

/** Provenance classification stored in {@code workflow_runtime.quarantine_record.quarantine_origin}. */
public enum QuarantineOrigin {
    COMMAND_UNKNOWN,
    TRANSFER_IN_FLIGHT,
    HARDWARE_FAULT,
    MANUAL_OR_LEGACY;

    public boolean requiresCommandId() {
        return this == COMMAND_UNKNOWN || this == TRANSFER_IN_FLIGHT;
    }
}
