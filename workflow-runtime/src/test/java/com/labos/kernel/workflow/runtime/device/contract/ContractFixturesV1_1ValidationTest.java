package com.labos.kernel.workflow.runtime.device.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labos.kernel.workflow.runtime.graph.GraphPlan;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates frozen cross-language golden fixtures in {@code contract-fixtures/v1_1/} against
 * {@code MANIFEST.sha256} and shared {@code device/contract/**} Java types.
 */
class ContractFixturesV1_1ValidationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Path resolveFixturesDir() {
        Path direct = Path.of("contract-fixtures/v1_1");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path parent = Path.of("../contract-fixtures/v1_1");
        if (Files.isDirectory(parent)) {
            return parent;
        }
        throw new IllegalStateException("Cannot locate contract-fixtures/v1_1 from " + Path.of("").toAbsolutePath());
    }

    @Test
    void manifestSha256MatchesAllGoldenFixturesFailClosed() throws Exception {
        Path dir = resolveFixturesDir();
        Path manifest = dir.resolve("MANIFEST.sha256");
        assertTrue(Files.isRegularFile(manifest), "Missing MANIFEST.sha256");
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        assertEquals(10, lines.size(), "MANIFEST.sha256 must list all 10 Stage 0 fixtures");

        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (String line : lines) {
            String[] parts = line.split("\\s+", 2);
            assertEquals(2, parts.length, "Malformed manifest line: " + line);
            String expectedHash = parts[0].trim();
            String fileName = parts[1].trim();
            Path file = dir.resolve(fileName);
            assertTrue(Files.isRegularFile(file), "Missing fixture listed in manifest: " + fileName);
            String actualHash = HexFormat.of().formatHex(sha.digest(Files.readAllBytes(file)));
            assertEquals(expectedHash, actualHash, "Fixture checksum drift detected: " + fileName);
        }
    }

    @Test
    void fixturesCoverAll13WireFrameTypesAndValidEnumsAndAntiForgeryRules() throws Exception {
        Path dir = resolveFixturesDir();
        EnumSet<WireFrameType> seenFrames = EnumSet.noneOf(WireFrameType.class);

        for (int i = 1; i <= 8; i++) {
            final String prefix = String.format("%02d_", i);
            Path file;
            try (var stream = Files.list(dir)) {
                file = stream
                        .filter(p -> p.getFileName().toString().startsWith(prefix))
                        .findFirst()
                        .orElseThrow();
            }
            JsonNode root = JSON.readTree(file.toFile());
            assertEquals(WireFrameV1.PROTOCOL_VERSION, root.get("protocolVersion").asText());
            for (JsonNode frame : root.get("frames")) {
                WireFrameType ft = WireFrameType.valueOf(frame.get("frameType").asText());
                seenFrames.add(ft);

                Map<String, Object> fields = JSON.convertValue(frame, LinkedHashMap.class);
                WireFrameV1 validated = new WireFrameV1(
                        frame.get("protocolVersion").asText(),
                        ft,
                        frame.get("sentAtEpochMs").asLong(),
                        fields);
                assertEquals(ft, validated.frameType());

                if (frame.has("storageState")) {
                    StorageState.valueOf(frame.get("storageState").asText());
                }
                if (frame.has("receiptStatus")) {
                    ReceiptStatus.valueOf(frame.get("receiptStatus").asText());
                }
                if (frame.has("failureBoundary")) {
                    FailureBoundary.valueOf(frame.get("failureBoundary").asText());
                }
                if (frame.has("reconciliationStatus")) {
                    ReconciliationStatus.valueOf(frame.get("reconciliationStatus").asText());
                }
                if (frame.has("evidenceSource") && frame.has("evidenceStrength")) {
                    Map<String, Object> evPayload = frame.has("evidencePayload")
                            ? JSON.convertValue(frame.get("evidencePayload"), Map.class)
                            : Map.of();
                    ExecutionEvidence ev = new ExecutionEvidence(
                            frame.hasNonNull("deviceSequence") ? frame.get("deviceSequence").asText() : null,
                            EvidenceSource.valueOf(frame.get("evidenceSource").asText()),
                            EvidenceStrength.valueOf(frame.get("evidenceStrength").asText()),
                            evPayload);
                    ev.validateWireInbound();
                }
                if (frame.hasNonNull("errorCode")) {
                    assertTrue(ContractV1ErrorCodes.ALL_CODES.contains(frame.get("errorCode").asText()),
                            "Unknown contract errorCode: " + frame.get("errorCode").asText());
                }
            }
        }
        assertEquals(EnumSet.allOf(WireFrameType.class), seenFrames,
                "Golden fixtures 01..08 must cover all 13 NDJSON v1.1 wire frame types");

        JsonNode negRoot = JSON.readTree(dir.resolve("09_negative_forbidden_wire_evidence.json").toFile());
        List<String> negIds = new ArrayList<>();
        for (JsonNode neg : negRoot.get("negativeCases")) {
            negIds.add(neg.get("caseId").asText());
            String expectedErr = neg.get("expectedErrorCode").asText();
            assertTrue(ContractV1ErrorCodes.ALL_CODES.contains(expectedErr));
            JsonNode frame = neg.get("frame");
            ExecutionEvidence ev = new ExecutionEvidence(
                    null,
                    EvidenceSource.valueOf(frame.get("evidenceSource").asText()),
                    EvidenceStrength.valueOf(frame.get("evidenceStrength").asText()),
                    JSON.convertValue(frame.get("evidencePayload"), Map.class));
            if (ContractV1ErrorCodes.ERR_WIRE_FORBIDDEN_EVIDENCE_SOURCE.equals(expectedErr)) {
                IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, ev::validateWireInbound);
                assertTrue(ex.getMessage().contains(ContractV1ErrorCodes.ERR_WIRE_FORBIDDEN_EVIDENCE_SOURCE));
            } else {
                assertFalse(ev.isStrongAutomaticSuccess());
            }
        }
        assertEquals(3, negIds.size());

        JsonNode v3Root = JSON.readTree(dir.resolve("10_v3_wait_deadline_policy.json").toFile());
        assertEquals(GraphPlan.SCHEMA_VERSION_V3, v3Root.get("schemaVersion").asInt());
        assertEquals(
                Set.of("ROUTE_TIMEOUT_EDGE", "ESCALATE_MANUAL", "FAIL_RUN"),
                Set.of(
                        v3Root.get("policies").get(0).get("deadlinePolicy").asText(),
                        v3Root.get("policies").get(1).get("deadlinePolicy").asText(),
                        v3Root.get("policies").get(2).get("deadlinePolicy").asText()));
    }
}
