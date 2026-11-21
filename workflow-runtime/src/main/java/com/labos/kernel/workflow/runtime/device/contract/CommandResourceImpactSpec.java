package com.labos.kernel.workflow.runtime.device.contract;

import java.util.List;

/**
 * Core-authoritative resource lease, transfer mutation, and quarantine blast-radius declaration
 * required prior to dispatching any physical device command ({@code PhysicalSafetyGate}).
 */
public record CommandResourceImpactSpec(
        String deviceId,
        String operationName,
        List<String> requiredExclusiveLeases,
        List<String> postSuccessMutations,
        List<String> quarantineScopeOnUnknown) {

    public CommandResourceImpactSpec {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId is required");
        }
        if (operationName == null || operationName.isBlank()) {
            throw new IllegalArgumentException("operationName is required");
        }
        requiredExclusiveLeases = requiredExclusiveLeases == null ? List.of() : List.copyOf(requiredExclusiveLeases);
        postSuccessMutations = postSuccessMutations == null ? List.of() : List.copyOf(postSuccessMutations);
        quarantineScopeOnUnknown = quarantineScopeOnUnknown == null ? List.of() : List.copyOf(quarantineScopeOnUnknown);
    }
}
