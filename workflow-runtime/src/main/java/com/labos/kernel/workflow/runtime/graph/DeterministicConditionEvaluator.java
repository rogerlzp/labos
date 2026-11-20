package com.labos.kernel.workflow.runtime.graph;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * Small deterministic expression language for BPMN sequence-flow conditions.
 *
 * Supported: literals, dotted map paths, !, &&, ||, ==, !=, >, >=, <, <= and parentheses.
 * No reflection, method calls, arithmetic, implicit truthiness, or environment access.
 */
public final class DeterministicConditionEvaluator {
    public boolean evaluate(String expression, Map<String, ?> variables) {
        if (expression == null || expression.isBlank())
            throw new IllegalArgumentException("Condition expression is empty");
        Objects.requireNonNull(variables, "variables");
        Parser parser = new Parser(expression, variables);
        Object result = parser.parse();
        if (!(result instanceof Boolean value))
            throw new IllegalArgumentException("Condition expression must evaluate to boolean");
        return value;
    }

    private static final class Parser {
        private final String source;
        private final Map<String, ?> variables;
        private int pos;

        Parser(String source, Map<String, ?> variables) {
            this.source = source;
            this.variables = variables;
        }

        Object parse() {
            Object value = or();
            whitespace();
            if (pos != source.length()) fail("Unexpected token");
            return value;
        }

        private Object or() {
            Object left = and();
            while (take("||")) {
                boolean a = bool(left, "left operand of ||");
                Object right = and();
                left = a || bool(right, "right operand of ||");
            }
            return left;
        }

        private Object and() {
            Object left = equality();
            while (take("&&")) {
                boolean a = bool(left, "left operand of &&");
                Object right = equality();
                left = a && bool(right, "right operand of &&");
            }
            return left;
        }

        private Object equality() {
            Object left = comparison();
            while (true) {
                if (take("==")) left = equal(left, comparison());
                else if (take("!=")) left = !equal(left, comparison());
                else return left;
            }
        }

        private Object comparison() {
            Object left = unary();
            while (true) {
                if (take(">=")) left = compare(left, unary()) >= 0;
                else if (take("<=")) left = compare(left, unary()) <= 0;
                else if (take(">")) left = compare(left, unary()) > 0;
                else if (take("<")) left = compare(left, unary()) < 0;
                else return left;
            }
        }

        private Object unary() {
            if (take("!")) return !bool(unary(), "operand of !");
            return primary();
        }

        private Object primary() {
            whitespace();
            if (take("(")) {
                Object value = or();
                if (!take(")")) fail("Missing ')'");
                return value;
            }
            if (pos >= source.length()) fail("Expected value");
            char c = source.charAt(pos);
            if (c == '"' || c == '\'') return string();
            if (c == '-' || Character.isDigit(c)) return number();
            if (Character.isJavaIdentifierStart(c)) return identifier();
            fail("Unsupported character '" + c + "'");
            return null;
        }

        private Object identifier() {
            int start = pos++;
            while (pos < source.length()) {
                char c = source.charAt(pos);
                if (Character.isJavaIdentifierPart(c) || c == '.') pos++;
                else break;
            }
            String name = source.substring(start, pos);
            return switch (name) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                case "null" -> null;
                default -> resolve(name);
            };
        }

        private Object resolve(String path) {
            String[] parts = path.split("\\.", -1);
            Object value = variables;
            for (String part : parts) {
                if (part.isEmpty()) fail("Invalid variable path: " + path);
                if (!(value instanceof Map<?, ?> map) || !map.containsKey(part))
                    fail("Unknown variable: " + path);
                value = map.get(part);
            }
            if (value != null && !(value instanceof String || value instanceof Boolean
                    || value instanceof Number))
                fail("Unsupported variable type: " + path);
            return value instanceof Number n ? new BigDecimal(n.toString()) : value;
        }

        private String string() {
            char quote = source.charAt(pos++);
            StringBuilder out = new StringBuilder();
            while (pos < source.length()) {
                char c = source.charAt(pos++);
                if (c == quote) return out.toString();
                if (c == '\\') {
                    if (pos >= source.length()) fail("Unterminated escape");
                    char escaped = source.charAt(pos++);
                    switch (escaped) {
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case '\\' -> out.append('\\');
                        case '"' -> out.append('"');
                        case '\'' -> out.append('\'');
                        default -> fail("Unsupported string escape");
                    }
                } else out.append(c);
            }
            fail("Unterminated string");
            return null;
        }

        private BigDecimal number() {
            int start = pos;
            if (source.charAt(pos) == '-') pos++;
            boolean digits = false;
            while (pos < source.length() && Character.isDigit(source.charAt(pos))) { pos++; digits = true; }
            if (pos < source.length() && source.charAt(pos) == '.') {
                pos++;
                while (pos < source.length() && Character.isDigit(source.charAt(pos))) { pos++; digits = true; }
            }
            if (!digits) fail("Invalid number");
            if (pos < source.length() && (source.charAt(pos) == 'e' || source.charAt(pos) == 'E')) {
                pos++;
                if (pos < source.length() && (source.charAt(pos) == '+' || source.charAt(pos) == '-')) pos++;
                int exponentStart = pos;
                while (pos < source.length() && Character.isDigit(source.charAt(pos))) pos++;
                if (exponentStart == pos) fail("Invalid exponent");
            }
            try { return new BigDecimal(source.substring(start, pos)); }
            catch (NumberFormatException e) { throw error("Invalid number"); }
        }

        private boolean take(String token) {
            whitespace();
            if (!source.startsWith(token, pos)) return false;
            if ((token.equals(">") || token.equals("<") || token.equals("!") || token.equals("="))
                    && pos + 1 < source.length() && source.charAt(pos + 1) == '=') return false;
            if ((token.equals("&") || token.equals("|")) && pos + 1 < source.length()
                    && source.charAt(pos + 1) == token.charAt(0)) return false;
            pos += token.length();
            return true;
        }

        private void whitespace() {
            while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) pos++;
        }

        private static boolean bool(Object value, String where) {
            if (value instanceof Boolean b) return b;
            throw new IllegalArgumentException(where + " must be boolean");
        }

        private static boolean equal(Object a, Object b) {
            if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y) == 0;
            if (a == null || b == null) return a == b;
            if (a.getClass() != b.getClass())
                throw new IllegalArgumentException("Cannot compare values of different types");
            return a.equals(b);
        }

        private static int compare(Object a, Object b) {
            if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y);
            if (a instanceof String x && b instanceof String y) return x.compareTo(y);
            throw new IllegalArgumentException("Ordering requires two numbers or two strings");
        }

        private void fail(String message) { throw error(message); }
        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at offset " + pos + " in condition: " + source);
        }
    }
}
