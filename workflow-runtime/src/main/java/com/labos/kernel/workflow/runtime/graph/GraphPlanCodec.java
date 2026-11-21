package com.labos.kernel.workflow.runtime.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Small dependency-free canonical JSON writer for persisted immutable GraphPlan snapshots. */
public final class GraphPlanCodec {
    private static final ObjectMapper JSON = new ObjectMapper();
    private GraphPlanCodec() {}

    /** Decode the immutable snapshot stored in definition_snapshot.graph_plan. */
    public static GraphPlan fromJson(String json) {
        return fromJson(json, null);
    }

    /** Decode the immutable snapshot with diagnostic Run/definition context and underlying Jackson cause. */
    public static GraphPlan fromJson(String json, String diagnosticContext) {
        String suffix = (diagnosticContext == null || diagnosticContext.isBlank())
                ? "" : " [" + diagnosticContext + "]";
        if (json == null) {
            throw new IllegalArgumentException("Invalid persisted GraphPlan JSON (null payload)" + suffix);
        }
        try {
            JsonNode root = JSON.readTree(json);
            if (root == null || root.isNull() || root.isMissingNode()) {
                throw new IllegalArgumentException("Invalid persisted GraphPlan JSON (null payload)" + suffix);
            }
            if (!root.isObject()) {
                throw new IllegalArgumentException(
                        "Invalid persisted GraphPlan JSON (expected object payload)" + suffix);
            }
            JsonNode versionNode = root.get("schemaVersion");
            if (versionNode == null) {
                throw new IOException("Missing required integer field: schemaVersion");
            }
            if (versionNode.isNull() || !versionNode.isIntegralNumber() || !versionNode.canConvertToInt()) {
                throw new IllegalArgumentException(
                        "Invalid persisted GraphPlan JSON (schemaVersion must be an integer)" + suffix);
            }
            int schemaVersion = versionNode.intValue();
            if (schemaVersion != GraphPlan.SCHEMA_VERSION_V1
                    && schemaVersion != GraphPlan.SCHEMA_VERSION_V2
                    && schemaVersion != GraphPlan.SCHEMA_VERSION_V3) {
                throw new IllegalArgumentException(
                        "Unsupported GraphPlan schemaVersion: " + schemaVersion + suffix);
            }
            if (schemaVersion == GraphPlan.SCHEMA_VERSION_V1) {
                rejectV2FieldsInV1Payload(root, suffix);
            }
            if (schemaVersion < GraphPlan.SCHEMA_VERSION_V3) {
                rejectV3FieldsInPreV3Payload(root, schemaVersion, suffix);
            }
            GraphPlan plan = JSON.treeToValue(root, GraphPlan.class);
            if (plan == null) {
                throw new IllegalArgumentException("Invalid persisted GraphPlan JSON (null payload)" + suffix);
            }
            if (plan.schemaVersion() != GraphPlan.SCHEMA_VERSION_V1
                    && plan.schemaVersion() != GraphPlan.SCHEMA_VERSION_V2
                    && plan.schemaVersion() != GraphPlan.SCHEMA_VERSION_V3) {
                throw new IllegalArgumentException(
                        "Unsupported GraphPlan schemaVersion: " + plan.schemaVersion() + suffix);
            }
            return plan;
        } catch (IOException e) {
            String causeSummary = e.getMessage() == null
                    ? e.getClass().getSimpleName()
                    : e.getClass().getSimpleName() + ": " + e.getMessage();
            throw new IllegalArgumentException(
                    "Invalid persisted GraphPlan JSON" + suffix + " (" + causeSummary + ")", e);
        }
    }

    private static void rejectV2FieldsInV1Payload(JsonNode root, String suffix) {
        JsonNode nodesNode = root.get("nodes");
        if (nodesNode != null && nodesNode.isArray()) {
            for (JsonNode n : nodesNode) {
                JsonNode kindNode = n.get("kind");
                if ((kindNode != null && "WAIT".equals(kindNode.asText(null)))
                        || n.hasNonNull("waitSpec")) {
                    throw new IllegalArgumentException(
                            "GraphPlan schemaVersion 1 cannot contain WAIT nodes or waitSpec" + suffix);
                }
            }
        }
        JsonNode edgesNode = root.get("edges");
        if (edgesNode != null && edgesNode.isArray()) {
            for (JsonNode e : edgesNode) {
                if (e.hasNonNull("routeKind")) {
                    throw new IllegalArgumentException(
                            "GraphPlan schemaVersion 1 cannot contain edge routeKind" + suffix);
                }
            }
        }
    }

    private static void rejectV3FieldsInPreV3Payload(JsonNode root, int schemaVersion, String suffix) {
        JsonNode nodesNode = root.get("nodes");
        if (nodesNode != null && nodesNode.isArray()) {
            for (JsonNode n : nodesNode) {
                JsonNode waitSpecNode = n.get("waitSpec");
                if (waitSpecNode != null && waitSpecNode.hasNonNull("deadlinePolicy")) {
                    throw new IllegalArgumentException(
                            "GraphPlan schemaVersion " + schemaVersion
                                    + " cannot contain waitSpec.deadlinePolicy" + suffix);
                }
            }
        }
    }

    public static void verifyBpmnHash(GraphPlan plan, String expectedSha256) {
        if (expectedSha256 == null || !expectedSha256.equalsIgnoreCase(plan.bpmnSha256()))
            throw new IllegalArgumentException("GraphPlan BPMN hash mismatch");
    }

    public static String toJson(GraphPlan plan) {
        boolean includeV2Fields = plan.schemaVersion() >= GraphPlan.SCHEMA_VERSION_V2;
        boolean includeV3Fields = plan.schemaVersion() >= GraphPlan.SCHEMA_VERSION_V3;
        StringBuilder b = new StringBuilder(1024);
        b.append("{\"schemaVersion\":").append(plan.schemaVersion())
         .append(",\"compilerVersion\":").append(q(plan.compilerVersion()))
         .append(",\"definitionKey\":").append(q(plan.definitionKey()))
         .append(",\"definitionVersion\":").append(q(plan.definitionVersion()))
         .append(",\"bpmnSha256\":").append(q(plan.bpmnSha256()))
         .append(",\"nodes\":[");
        for (int i=0;i<plan.nodes().size();i++) {
            if (i>0) b.append(',');
            GraphPlan.Node n=plan.nodes().get(i);
            b.append("{\"key\":").append(q(n.key()))
             .append(",\"name\":").append(q(n.name()))
             .append(",\"kind\":").append(q(n.kind().name()))
             .append(",\"sourceType\":").append(q(n.sourceType()))
             .append(",\"protocol\":").append(nullable(n.protocol()))
             .append(",\"resourceId\":").append(n.resourceId()==null?"null":n.resourceId())
             .append(",\"methodParams\":").append(nullable(n.methodParams()))
             .append(",\"stageCode\":").append(nullable(n.stageCode()))
             .append(",\"capabilityCode\":").append(nullable(n.capabilityCode()))
             .append(",\"workflowNodeId\":").append(n.workflowNodeId()==null?"null":n.workflowNodeId());
            if (includeV2Fields) {
                b.append(",\"waitSpec\":").append(waitSpecJson(n.waitSpec(), includeV3Fields));
            }
            b.append('}');
        }
        b.append("],\"edges\":[");
        for (int i=0;i<plan.edges().size();i++) {
            if (i>0) b.append(',');
            GraphPlan.Edge e=plan.edges().get(i);
            b.append("{\"key\":").append(q(e.key()))
             .append(",\"from\":").append(q(e.from()))
             .append(",\"to\":").append(q(e.to()))
             .append(",\"condition\":").append(nullable(e.condition()))
             .append(",\"defaultFlow\":").append(e.defaultFlow());
            if (includeV2Fields) {
                b.append(",\"routeKind\":").append(nullable(e.routeKind() == null ? null : e.routeKind().name()));
            }
            b.append('}');
        }
        return b.append("]}").toString();
    }

    private static String waitSpecJson(GraphPlan.WaitSpec waitSpec, boolean includeV3Fields) {
        if (waitSpec == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder();
        b.append("{\"waitKind\":").append(q(waitSpec.waitKind().name()))
         .append(",\"correlationKey\":").append(nullable(waitSpec.correlationKey()))
         .append(",\"durationIso\":").append(nullable(waitSpec.durationIso()))
         .append(",\"durationMillis\":").append(waitSpec.durationMillis() == null ? "null" : waitSpec.durationMillis());
        if (includeV3Fields) {
            b.append(",\"deadlinePolicy\":").append(
                    nullable(waitSpec.deadlinePolicy() == null ? null : waitSpec.deadlinePolicy().name()));
        }
        return b.append('}').toString();
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String nullable(String s) { return s == null ? "null" : q(s); }
    private static String q(String s) {
        StringBuilder b=new StringBuilder("\"");
        for (int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            switch(c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if(c<0x20) b.append(String.format("\\u%04x",(int)c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }
}
