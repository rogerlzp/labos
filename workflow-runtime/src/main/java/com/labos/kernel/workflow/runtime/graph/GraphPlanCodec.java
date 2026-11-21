package com.labos.kernel.workflow.runtime.graph;

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
        try {
            GraphPlan plan = JSON.readValue(json, GraphPlan.class);
            if (plan == null) {
                throw new IllegalArgumentException("Invalid persisted GraphPlan JSON (null payload)" + suffix);
            }
            if (plan.schemaVersion() != 1) {
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

    public static void verifyBpmnHash(GraphPlan plan, String expectedSha256) {
        if (expectedSha256 == null || !expectedSha256.equalsIgnoreCase(plan.bpmnSha256()))
            throw new IllegalArgumentException("GraphPlan BPMN hash mismatch");
    }

    public static String toJson(GraphPlan plan) {
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
             .append(",\"workflowNodeId\":").append(n.workflowNodeId()==null?"null":n.workflowNodeId())
             .append('}');
        }
        b.append("],\"edges\":[");
        for (int i=0;i<plan.edges().size();i++) {
            if (i>0) b.append(',');
            GraphPlan.Edge e=plan.edges().get(i);
            b.append("{\"key\":").append(q(e.key()))
             .append(",\"from\":").append(q(e.from()))
             .append(",\"to\":").append(q(e.to()))
             .append(",\"condition\":").append(nullable(e.condition()))
             .append(",\"defaultFlow\":").append(e.defaultFlow()).append('}');
        }
        return b.append("]}").toString();
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
