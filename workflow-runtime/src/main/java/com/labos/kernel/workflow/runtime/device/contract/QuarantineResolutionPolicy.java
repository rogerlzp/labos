package com.labos.kernel.workflow.runtime.device.contract;

/** Explicit quarantine group resolution choice required by T21 {@code reconcileAndSettleUnknownCommand}. */
public enum QuarantineResolutionPolicy {
    CLEAR_AFTER_VERIFY,
    KEEP_QUARANTINED
}
