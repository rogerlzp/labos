package com.labos.kernel.workflow.runtime.device.contract;

/** Origin of completion, abort, or indeterminate evidence for a physical device command. */
public enum EvidenceSource {
    HARDWARE_NATIVE_QUERY,
    PRIOR_HARDWARE_COMPLETION_IN_JOURNAL,
    PRE_SEND_ABORT_IN_JOURNAL,
    OPERATOR_PHYSICAL_INSPECTION,
    AMBIGUOUS;

    /** Line protocol frames must never carry T21 human-only evidence sources. */
    public boolean isWireAllowed() {
        return this != OPERATOR_PHYSICAL_INSPECTION;
    }
}
