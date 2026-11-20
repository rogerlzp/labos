package com.labos.kernel.workflow.runtime.gateway;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Strict protocol-boundary unit tests for {@link LabSepWireCodec#decodeInboundFrame(String)}.
 * Ensures unknown {@code method_status} values and missing/non-integer/unknown {@code message_type}
 * values are ALWAYS rejected instead of being misclassified as physical {@code FAILED} or {@code SUCCEEDED}.
 */
class LabSepWireCodecTest {

    private static final UUID CMD_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void decodesValidMessageType5WithMethodStatus123() throws Exception {
        // method_status = 1 -> PROGRESS / RUNNING
        String f1 = """
                {"message_id":"%s","message_type":5,"method":"Dispense","equipment_id":"206","method_status":1,"error":null,"data":{"pct":40},"timestamp":"2026-11-21 07:00:01"}
                """.formatted(CMD_ID);
        var decoded1 = assertInstanceOf(GatewayWireProtocol.DeviceEventFrame.class,
                LabSepWireCodec.INSTANCE.decodeInboundFrame(f1));
        assertEquals(CMD_ID, decoded1.commandId());
        assertNull(decoded1.runId());
        assertEquals("206", decoded1.claimedDeviceId());
        assertEquals(CMD_ID + ":status:1", decoded1.sourceMessageId());
        assertEquals("PROGRESS", decoded1.messageType());
        assertEquals("RUNNING", decoded1.targetState());

        // method_status = 2 -> COMMAND_RESULT / SUCCEEDED
        String f2 = """
                {"message_id":"%s","message_type":5,"method":"Dispense","equipment_id":"206","method_status":2,"error":null,"data":{"volume":150},"timestamp":"2026-11-21 07:00:02"}
                """.formatted(CMD_ID);
        var decoded2 = assertInstanceOf(GatewayWireProtocol.DeviceEventFrame.class,
                LabSepWireCodec.INSTANCE.decodeInboundFrame(f2));
        assertEquals(CMD_ID + ":status:2", decoded2.sourceMessageId());
        assertEquals("COMMAND_RESULT", decoded2.messageType());
        assertEquals("SUCCEEDED", decoded2.targetState());
        assertTrue(decoded2.outcomeJson().contains("150"));

        // method_status = 3 -> COMMAND_RESULT / FAILED
        String f3 = """
                {"message_id":"%s","message_type":5,"method":"Dispense","equipment_id":"206","method_status":3,"error":{"error_code":"EC-05-0001","error_text":"jam"},"data":{},"timestamp":"2026-11-21 07:00:03"}
                """.formatted(CMD_ID);
        var decoded3 = assertInstanceOf(GatewayWireProtocol.DeviceEventFrame.class,
                LabSepWireCodec.INSTANCE.decodeInboundFrame(f3));
        assertEquals(CMD_ID + ":status:3", decoded3.sourceMessageId());
        assertEquals("COMMAND_RESULT", decoded3.messageType());
        assertEquals("FAILED", decoded3.targetState());
        assertTrue(decoded3.outcomeJson().contains("EC-05-0001"));
    }

    @Test
    void rejectsUnknownMissingAndNonIntegerMethodStatus() {
        String[] invalidFrames = {
                // method_status = 0
                "{\"message_id\":\"" + CMD_ID + "\",\"message_type\":5,\"equipment_id\":\"206\",\"method_status\":0,\"error\":null}",
                // method_status = 4
                "{\"message_id\":\"" + CMD_ID + "\",\"message_type\":5,\"equipment_id\":\"206\",\"method_status\":4,\"error\":null}",
                // method_status = -1
                "{\"message_id\":\"" + CMD_ID + "\",\"message_type\":5,\"equipment_id\":\"206\",\"method_status\":-1,\"error\":null}",
                // method_status missing
                "{\"message_id\":\"" + CMD_ID + "\",\"message_type\":5,\"equipment_id\":\"206\",\"error\":null}",
                // method_status null
                "{\"message_id\":\"" + CMD_ID + "\",\"message_type\":5,\"equipment_id\":\"206\",\"method_status\":null,\"error\":null}",
                // method_status string "2"
                "{\"message_id\":\"" + CMD_ID + "\",\"message_type\":5,\"equipment_id\":\"206\",\"method_status\":\"2\",\"error\":null}",
                // method_status string "3"
                "{\"message_id\":\"" + CMD_ID + "\",\"message_type\":5,\"equipment_id\":\"206\",\"method_status\":\"3\",\"error\":null}",
                // contradictory method_status=1 with non-empty error
                "{\"message_id\":\"" + CMD_ID + "\",\"message_type\":5,\"equipment_id\":\"206\",\"method_status\":1,\"error\":{\"error_code\":\"E1\"}}",
                // contradictory method_status=2 with non-empty error
                "{\"message_id\":\"" + CMD_ID + "\",\"message_type\":5,\"equipment_id\":\"206\",\"method_status\":2,\"error\":{\"error_code\":\"E2\"}}"
        };
        for (String frame : invalidFrames) {
            assertThrows(IllegalArgumentException.class,
                    () -> LabSepWireCodec.INSTANCE.decodeInboundFrame(frame),
                    "Frame must be rejected instead of mapped to FAILED/SUCCEEDED: " + frame);
        }
    }

    @Test
    void rejectsMissingNonIntegerAndUnknownMessageType() {
        String[] invalidFrames = {
                // message_type missing
                "{\"message_id\":\"" + CMD_ID + "\",\"equipment_id\":\"206\",\"method_status\":2,\"error\":null}",
                // message_type null
                "{\"message_id\":\"" + CMD_ID + "\",\"message_type\":null,\"equipment_id\":\"206\",\"method_status\":2,\"error\":null}",
                // message_type string "5"
                "{\"message_id\":\"" + CMD_ID + "\",\"message_type\":\"5\",\"equipment_id\":\"206\",\"method_status\":2,\"error\":null}",
                // message_type = 1 (outbound command type echoed back)
                "{\"message_id\":\"" + CMD_ID + "\",\"message_type\":1,\"equipment_id\":\"206\",\"method_status\":2,\"error\":null}",
                // message_type = 0 (unknown)
                "{\"message_id\":\"" + CMD_ID + "\",\"message_type\":0,\"equipment_id\":\"206\",\"method_status\":2,\"error\":null}",
                // message_type = 99 (unknown)
                "{\"message_id\":\"" + CMD_ID + "\",\"message_type\":99,\"equipment_id\":\"206\",\"method_status\":2,\"error\":null}"
        };
        for (String frame : invalidFrames) {
            assertThrows(IllegalArgumentException.class,
                    () -> LabSepWireCodec.INSTANCE.decodeInboundFrame(frame),
                    "Invalid message_type frame must be rejected: " + frame);
        }
    }

    @Test
    void ignoresSpontaneousMessageType6AndUnsolicitedBroadcastSafely() throws Exception {
        // Real DeviceCore message_type=6 spontaneous event (no message_id)
        assertNull(LabSepWireCodec.INSTANCE.decodeInboundFrame(
                "{\"message_type\":6,\"equipment_id\":\"LH-01\",\"event_type\":\"process_done\",\"data\":{}}"));
        // Spontaneous message_type=6 even if driver attaches a UUID message_id
        assertNull(LabSepWireCodec.INSTANCE.decodeInboundFrame(
                "{\"message_id\":\"" + CMD_ID + "\",\"message_type\":6,\"equipment_id\":\"LH-01\",\"event_type\":\"waiting_interaction\"}"));
        // Unsolicited message_type=5 status broadcast without message_id
        assertNull(LabSepWireCodec.INSTANCE.decodeInboundFrame(
                "{\"message_type\":5,\"equipment_status\":1,\"connected\":1}"));
        // Non-UUID message_id not issued by Workflow Runtime
        assertNull(LabSepWireCodec.INSTANCE.decodeInboundFrame(
                "{\"message_id\":\"legacy-seq-42\",\"message_type\":5,\"method_status\":2}"));
    }
}
