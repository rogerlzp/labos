package com.labos.kernel.workflow.runtime.graph;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic evaluator for the deliberately small BPMN sequence-flow expression subset. */
public final class RestrictedExpressionEvaluator {
    private RestrictedExpressionEvaluator() {}

    public static boolean evaluateBoolean(String expression, Map<String, ?> variables) {
        if (expression == null || expression.isBlank()) throw new IllegalArgumentException("Condition expression is blank");
        Objects.requireNonNull(variables, "variables");
        Parser parser = new Parser(expression);
        Expr result = parser.parseExpression();
        parser.expect(TokenType.END);
        Object value = result.evaluate(variables);
        if (!(value instanceof Boolean bool)) throw new IllegalArgumentException("Condition expression must evaluate to boolean");
        return bool;
    }

    private interface Expr { Object evaluate(Map<String, ?> variables); }
    private record Literal(Object value) implements Expr {
        public Object evaluate(Map<String, ?> variables) { return value; }
    }
    private record Variable(String name) implements Expr {
        public Object evaluate(Map<String, ?> variables) {
            if (!variables.containsKey(name)) throw new IllegalArgumentException("Unknown condition variable: " + name);
            Object value = variables.get(name);
            if (value != null && !(value instanceof String || value instanceof Boolean || value instanceof Number))
                throw new IllegalArgumentException("Unsupported variable value type: " + name);
            return value;
        }
    }
    private record Unary(Expr operand) implements Expr {
        public Object evaluate(Map<String, ?> variables) {
            Object value = operand.evaluate(variables);
            if (!(value instanceof Boolean bool)) throw new IllegalArgumentException("Operator ! requires boolean");
            return !bool;
        }
    }
    private record Binary(Expr left, TokenType operator, Expr right) implements Expr {
        public Object evaluate(Map<String, ?> variables) {
            Object a = left.evaluate(variables);
            if (operator == TokenType.AND) {
                if (!(a instanceof Boolean b)) throw new IllegalArgumentException("Operator && requires boolean operands");
                return b && requireBoolean(right.evaluate(variables), "&&");
            }
            if (operator == TokenType.OR) {
                if (!(a instanceof Boolean b)) throw new IllegalArgumentException("Operator || requires boolean operands");
                return b || requireBoolean(right.evaluate(variables), "||");
            }
            Object b = right.evaluate(variables);
            return switch (operator) {
                case EQ -> equal(a, b);
                case NE -> !equal(a, b);
                case GT -> compare(a, b) > 0;
                case GE -> compare(a, b) >= 0;
                case LT -> compare(a, b) < 0;
                case LE -> compare(a, b) <= 0;
                default -> throw new IllegalStateException("Unexpected operator " + operator);
            };
        }
    }

    private static boolean requireBoolean(Object value, String operator) {
        if (!(value instanceof Boolean b)) throw new IllegalArgumentException("Operator " + operator + " requires boolean operands");
        return b;
    }
    private static boolean equal(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number an && b instanceof Number bn) return decimal(an).compareTo(decimal(bn)) == 0;
        if (a.getClass() != b.getClass())
            throw new IllegalArgumentException("Equality comparison requires operands of the same type");
        return Objects.equals(a, b);
    }
    private static int compare(Object a, Object b) {
        if (a instanceof Number an && b instanceof Number bn) return decimal(an).compareTo(decimal(bn));
        if (a instanceof String as && b instanceof String bs) return as.compareTo(bs);
        throw new IllegalArgumentException("Comparison requires two numbers or two strings");
    }
    private static BigDecimal decimal(Number value) {
        try { return new BigDecimal(value.toString()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Non-finite numeric value is not supported", e); }
    }

    private enum TokenType { IDENT, STRING, NUMBER, TRUE, FALSE, NULL, LPAREN, RPAREN, NOT, AND, OR, EQ, NE, GT, GE, LT, LE, END }
    private record Token(TokenType type, String text, Object value, int offset) {}

    private static final class Parser {
        private final String source;
        private final List<Token> tokens;
        private int cursor;
        Parser(String source) { this.source = source; this.tokens = lex(source); }
        Expr parseExpression() { return parseOr(); }
        private Expr parseOr() {
            Expr e = parseAnd();
            while (match(TokenType.OR)) e = new Binary(e, TokenType.OR, parseAnd());
            return e;
        }
        private Expr parseAnd() {
            Expr e = parseEquality();
            while (match(TokenType.AND)) e = new Binary(e, TokenType.AND, parseEquality());
            return e;
        }
        private Expr parseEquality() {
            Expr e = parseRelational();
            while (peek(TokenType.EQ) || peek(TokenType.NE)) {
                TokenType op = advance().type();
                e = new Binary(e, op, parseRelational());
            }
            return e;
        }
        private Expr parseRelational() {
            Expr e = parseUnary();
            while (peek(TokenType.GT) || peek(TokenType.GE) || peek(TokenType.LT) || peek(TokenType.LE)) {
                TokenType op = advance().type();
                e = new Binary(e, op, parseUnary());
            }
            return e;
        }
        private Expr parseUnary() { return match(TokenType.NOT) ? new Unary(parseUnary()) : parsePrimary(); }
        private Expr parsePrimary() {
            Token t = advance();
            return switch (t.type()) {
                case TRUE, FALSE, NULL, STRING, NUMBER -> new Literal(t.value());
                case IDENT -> new Variable(t.text());
                case LPAREN -> {
                    Expr nested = parseExpression();
                    expect(TokenType.RPAREN);
                    yield nested;
                }
                default -> throw error(t, "Expected literal, variable or parenthesized expression");
            };
        }
        private boolean match(TokenType t) { if (!peek(t)) return false; cursor++; return true; }
        private boolean peek(TokenType t) { return tokens.get(cursor).type() == t; }
        private Token advance() { return tokens.get(cursor++); }
        private void expect(TokenType t) {
            Token actual = advance();
            if (actual.type() != t) throw error(actual, "Expected " + t + " but found " + actual.type());
        }
        private IllegalArgumentException error(Token t, String message) {
            return new IllegalArgumentException(message + " at offset " + t.offset() + " in condition: " + source);
        }
    }

    private static List<Token> lex(String source) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            int start = i;
            if (Character.isLetter(c) || c == '_') {
                i++;
                while (i < source.length()) {
                    char n = source.charAt(i);
                    if (Character.isLetterOrDigit(n) || n == '_' || n == '.') i++; else break;
                }
                String word = source.substring(start, i);
                TokenType type = switch (word) {
                    case "true" -> TokenType.TRUE;
                    case "false" -> TokenType.FALSE;
                    case "null" -> TokenType.NULL;
                    default -> TokenType.IDENT;
                };
                Object value = type == TokenType.TRUE ? Boolean.TRUE : type == TokenType.FALSE ? Boolean.FALSE : null;
                out.add(new Token(type, word, value, start));
                continue;
            }
            if (Character.isDigit(c) || (c == '-' && i + 1 < source.length() && Character.isDigit(source.charAt(i + 1)))) {
                i++;
                while (i < source.length() && (Character.isDigit(source.charAt(i)) || source.charAt(i) == '.')) i++;
                String n = source.substring(start, i);
                try { out.add(new Token(TokenType.NUMBER, n, new BigDecimal(n), start)); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid number at offset " + start, e); }
                continue;
            }
            if (c == '\'' || c == '"') {
                char quote = c; i++;
                StringBuilder value = new StringBuilder();
                boolean closed = false;
                while (i < source.length()) {
                    char n = source.charAt(i++);
                    if (n == quote) { closed = true; break; }
                    if (n == '\\') {
                        if (i >= source.length()) throw new IllegalArgumentException("Unfinished string escape at offset " + start);
                        char escaped = source.charAt(i++);
                        switch (escaped) {
                            case '\\', '\'', '"' -> value.append(escaped);
                            case 'n' -> value.append('\n');
                            case 'r' -> value.append('\r');
                            case 't' -> value.append('\t');
                            default -> throw new IllegalArgumentException("Unsupported string escape at offset " + (i - 2));
                        }
                    } else value.append(n);
                }
                if (!closed) throw new IllegalArgumentException("Unterminated string at offset " + start);
                out.add(new Token(TokenType.STRING, source.substring(start, i), value.toString(), start));
                continue;
            }
            TokenType type;
            if (source.startsWith("&&", i)) { type = TokenType.AND; i += 2; }
            else if (source.startsWith("||", i)) { type = TokenType.OR; i += 2; }
            else if (source.startsWith("==", i)) { type = TokenType.EQ; i += 2; }
            else if (source.startsWith("!=", i)) { type = TokenType.NE; i += 2; }
            else if (source.startsWith(">=", i)) { type = TokenType.GE; i += 2; }
            else if (source.startsWith("<=", i)) { type = TokenType.LE; i += 2; }
            else {
                i++;
                type = switch (c) {
                    case '(' -> TokenType.LPAREN;
                    case ')' -> TokenType.RPAREN;
                    case '!' -> TokenType.NOT;
                    case '>' -> TokenType.GT;
                    case '<' -> TokenType.LT;
                    default -> throw new IllegalArgumentException("Unsupported character '" + c + "' at offset " + start);
                };
            }
            out.add(new Token(type, source.substring(start, i), null, start));
        }
        out.add(new Token(TokenType.END, "", null, source.length()));
        return List.copyOf(out);
    }
}
