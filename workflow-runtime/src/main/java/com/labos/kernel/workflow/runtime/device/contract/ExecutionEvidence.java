package com.labos.kernel.workflow.runtime.device.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Structured execution evidence quadruple separating correlation handles from verifiable facts.
 */
public record ExecutionEvidence(
        String deviceSequence,
        EvidenceSource evidenceSource,
        EvidenceStrength evidenceStrength,
        Map<String, Object> evidencePayload) {

    public ExecutionEvidence {
        deviceSequence = (deviceSequence == null || deviceSequence.isBlank()) ? null : deviceSequence;
        Objects.requireNonNull(evidenceSource, "evidenceSource");
        Objects.requireNonNull(evidenceStrength, "evidenceStrength");
        evidencePayload = (evidencePayload == null || evidencePayload.isEmpty())
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(evidencePayload));
    }

    /**
     * Enforces the anti-forgery boundary: wire frames must NEVER carry operator inspection claims,
     * which belong exclusively to authenticated Kernel T21 manual adjudication.
     */
    public void validateWireInbound() {
        if (!evidenceSource.isWireAllowed() || !evidenceStrength.isWireAllowed()) {
            throw new IllegalArgumentException(
                    ContractV1ErrorCodes.ERR_WIRE_FORBIDDEN_EVIDENCE_SOURCE
                            + ": wire frames cannot carry " + evidenceSource + " / " + evidenceStrength);
        }
    }

    /** Channel A gate: automatic completion requires strong hardware facts and non-empty payload. */
    public boolean isStrongAutomaticSuccess() {
        return evidenceStrength == EvidenceStrength.STRONG_HARDWARE_FACT
                && (evidenceSource == EvidenceSource.HARDWARE_NATIVE_QUERY
                        || evidenceSource == EvidenceSource.PRIOR_HARDWARE_COMPLETION_IN_JOURNAL)
                && !evidencePayload.isEmpty();
    }

    /** Channel B gate: T21 manual completion requires operator-attested strength + inspection + payload. */
    public boolean isStrongOperatorAttested() {
        return evidenceStrength == EvidenceStrength.STRONG_OPERATOR_ATTESTED
                && evidenceSource == EvidenceSource.OPERATOR_PHYSICAL_INSPECTION
                && !evidencePayload.isEmpty();
    }
}
