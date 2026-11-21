package com.labos.kernel.workflow.runtime.device.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Versioned NDJSON v1.1 wire frame header and body container. */
public record WireFrameV1(
        String protocolVersion,
        WireFrameType frameType,
        long sentAtEpochMs,
        Map<String, Object> fields) {

    public static final String PROTOCOL_VERSION = "1.1";

    public WireFrameV1 {
        if (!PROTOCOL_VERSION.equals(protocolVersion)) {
            throw new IllegalArgumentException(
                    ContractV1ErrorCodes.ERR_WIRE_UNSUPPORTED_VERSION
                            + ": expected protocolVersion=" + PROTOCOL_VERSION + ", got=" + protocolVersion);
        }
        Objects.requireNonNull(frameType, "frameType");
        if (sentAtEpochMs < 0) {
            throw new IllegalArgumentException("sentAtEpochMs must be non-negative");
        }
        fields = (fields == null || fields.isEmpty())
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
}
