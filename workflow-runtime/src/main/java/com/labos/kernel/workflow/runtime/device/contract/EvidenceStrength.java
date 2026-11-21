package com.labos.kernel.workflow.runtime.device.contract;

/** Verification strength determining legal command state transitions under Contract v1.x. */
public enum EvidenceStrength {
    STRONG_HARDWARE_FACT,
    STRONG_PRE_SEND_ABORT,
    STRONG_OPERATOR_ATTESTED,
    WEAK_UNVERIFIED;

    /** Line protocol frames must never assert T21 operator-attested evidence strength. */
    public boolean isWireAllowed() {
        return this != STRONG_OPERATOR_ATTESTED;
    }
}
