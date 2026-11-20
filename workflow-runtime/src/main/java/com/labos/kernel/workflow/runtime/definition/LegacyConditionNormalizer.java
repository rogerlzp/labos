package com.labos.kernel.workflow.runtime.definition;

import com.labos.kernel.workflow.runtime.graph.GraphPlan;

import java.util.List;
import java.util.Objects;

/**
 * Narrow legacy-definition adapter that converts legacy {@code ${identifier(.identifier)*}}
 * variable references in BPMN sequence-flow conditions into the bare identifier syntax
 * expected by {@code RestrictedExpressionEvaluator}.
 *
 * <p>Security &amp; provenance invariants:
 * <ul>
 *   <li>Only invoked on the legacy definition boundary ({@link LegacyKernelWorkflowDefinitionSource});
 *       {@code RestrictedExpressionEvaluator} itself remains strictly closed to {@code ${...}}.</li>
 *   <li>Original BPMN XML and its SHA-256 hash ({@code GraphPlan.bpmnSha256}) are never mutated.</li>
 *   <li>Single- and double-quoted string literals are skipped verbatim (e.g. {@code '${keep}'} stays unchanged).</li>
 *   <li>Only strict identifier paths matching {@code [A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*}
 *       (excluding literal keywords {@code true}/{@code false}/{@code null}) are unwrapped;
 *       arbitrary expressions such as {@code ${1+2}}, {@code ${System.exit(0)}}, or {@code ${a[0]}}
 *       are left untouched so {@code RestrictedExpressionEvaluator} rejects them fail-closed.</li>
 * </ul>
 */
public final class LegacyConditionNormalizer {
    private LegacyConditionNormalizer() {}

    /**
     * Returns a new {@link GraphPlan} with legacy {@code ${var}} placeholders normalized on edge
     * conditions while keeping the original {@code bpmnSha256}, version, and nodes untouched.
     */
    public static GraphPlan normalizePlan(GraphPlan plan) {
        Objects.requireNonNull(plan, "plan");
        boolean changed = false;
        List<GraphPlan.Edge> originalEdges = plan.edges();
        GraphPlan.Edge[] normalizedEdges = new GraphPlan.Edge[originalEdges.size()];
        for (int i = 0; i < originalEdges.size(); i++) {
            GraphPlan.Edge edge = originalEdges.get(i);
            String cond = edge.condition();
            String rewritten = normalize(cond);
            if (!Objects.equals(cond, rewritten)) {
                changed = true;
                normalizedEdges[i] = new GraphPlan.Edge(
                        edge.key(), edge.from(), edge.to(), rewritten, edge.defaultFlow());
            } else {
                normalizedEdges[i] = edge;
            }
        }
        if (!changed) {
            return plan;
        }
        return new GraphPlan(
                plan.schemaVersion(),
                plan.compilerVersion(),
                plan.definitionKey(),
                plan.definitionVersion(),
                plan.bpmnSha256(),
                plan.nodes(),
                List.of(normalizedEdges));
    }

    /**
     * Unwraps strict {@code ${ident(.ident)*}} placeholders outside string literals.
     * Non-matching {@code ${...}} constructs are left verbatim to fail closed downstream.
     */
    public static String normalize(String expression) {
        if (expression == null || !expression.contains("${")) {
            return expression;
        }
        int n = expression.length();
        StringBuilder out = new StringBuilder(n);
        int i = 0;
        while (i < n) {
            char c = expression.charAt(i);
            if (c == '\'' || c == '"') {
                char quote = c;
                out.append(c);
                i++;
                while (i < n) {
                    char sc = expression.charAt(i);
                    out.append(sc);
                    i++;
                    if (sc == '\\' && i < n) {
                        out.append(expression.charAt(i));
                        i++;
                    } else if (sc == quote) {
                        break;
                    }
                }
                continue;
            }
            if (c == '$' && i + 1 < n && expression.charAt(i + 1) == '{') {
                int close = expression.indexOf('}', i + 2);
                if (close > i + 2) {
                    String inner = expression.substring(i + 2, close);
                    boolean leftBoundaryOk = i == 0 || !isIdentPart(expression.charAt(i - 1));
                    boolean rightBoundaryOk = close + 1 >= n || !isIdentPart(expression.charAt(close + 1));
                    if (leftBoundaryOk && rightBoundaryOk && isStrictIdentifierPath(inner)) {
                        out.append(inner);
                        i = close + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean isStrictIdentifierPath(String candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        if ("true".equals(candidate) || "false".equals(candidate) || "null".equals(candidate)) {
            return false;
        }
        int len = candidate.length();
        int segStart = 0;
        while (segStart < len) {
            char first = candidate.charAt(segStart);
            if (!isIdentStart(first)) {
                return false;
            }
            int j = segStart + 1;
            while (j < len && isIdentPart(candidate.charAt(j))) {
                j++;
            }
            if (j == len) {
                return true;
            }
            if (candidate.charAt(j) != '.') {
                return false;
            }
            segStart = j + 1;
            if (segStart == len) {
                // trailing dot is invalid
                return false;
            }
        }
        return false;
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9');
    }
}
