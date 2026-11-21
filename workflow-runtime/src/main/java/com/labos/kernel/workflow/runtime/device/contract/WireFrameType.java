package com.labos.kernel.workflow.runtime.device.contract;

/** All 13 versioned NDJSON v1.1 wire frame types defined in DEVICE_GATEWAY_PROTOCOL.md §3.2. */
public enum WireFrameType {
    HELLO,
    HELLO_ACK,
    HEARTBEAT_PING,
    HEARTBEAT_PONG,
    COMMAND_SEND,
    COMMAND_RECEIPT,
    COMMAND_QUERY,
    COMMAND_QUERY_RESULT,
    COMMAND_CANCEL,
    DEVICE_EVENT,
    EVENT_ACK,
    EVENT_NACK,
    DEVICE_STATE
}
