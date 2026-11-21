package com.labos.kernel.workflow.runtime.device.contract;

import java.util.Map;

/** Per-device capability advertisement exchanged on {@code HELLO_ACK}. */
public record DeviceCapabilities(
        String deviceId,
        String deviceModel,
        Map<String, DeviceOperationCapability> operations) {

    public DeviceCapabilities {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId is required");
        }
        deviceModel = (deviceModel == null || deviceModel.isBlank()) ? null : deviceModel;
        operations = operations == null ? Map.of() : Map.copyOf(operations);
    }
}
