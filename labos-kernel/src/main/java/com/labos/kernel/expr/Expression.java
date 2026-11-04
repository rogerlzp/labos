package com.labos.kernel.expr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 表达式：解析一次，多次求值。
 *
 * <p>支持的能力（见 {@code docs/表达式引擎-设计.md}）：
 * <ul>
 *   <li>算术 {@code + - * / %}</li>
 *   <li>比较 {@code > >= < <= == !=}</li>
 *   <li>逻辑 {@code && || !}</li>
 *   <li>三元 {@code a ? b : c}</li>
 *   <li>括号</li>
 *   <li>变量（从上下文取）</li>
 *   <li>内置函数 {@code floor ceil round abs max min has}</li>
 * </ul>
 *
 * <p><b>故意不支持</b>：字符串函数、函数定义、循环、对象/数组、正则。
 * 表达式只做"算一个值"，不做"写逻辑"。
 *
 * <p><b>为什么自己写</b>：Nashorn 已在 JDK 15+ 移除，GraalJS 几十 MB，
 * 脚本引擎都能执行任意代码。自己写 300 行，零依赖、只能算数、行为可控。
 */
public final class Expression {

    /** 解析缓存：同一个表达式在循环里会被求值多次。 */
    private static final Map<String, Expression> CACHE = new ConcurrentHashMap<>();
    private static final int CACHE_LIMIT = 2000;

    private final String source;
    private final Node root;

    private Expression(String source, Node root) {
        this.source = source;
        this.root = root;
    }

    /** 解析（带缓存）。语法错抛 {@link ExprException}。 */
    public static Expression parse(String src) {
        Expression cached = CACHE.get(src);
        if (cached != null) {
            return cached;
        }
        Expression e = new Expression(src, new Parser(src).parseAll());
        if (CACHE.size() < CACHE_LIMIT) {
            CACHE.put(src, e);
        }
        return e;
    }

    /** 求值。变量未定义、除零、类型错都抛 {@link ExprException}。 */
    public Object eval(Map<String, Object> vars) {
        return Evaluator.eval(root, vars, source);
    }

    /** 求值并转成字符串（用于点位 code 拼接）。 */
    public String evalString(Map<String, Object> vars) {
        return Values.toText(eval(vars));
    }

    @Override
    public String toString() {
        return source;
    }

    // ================================================================== AST

    sealed interface Node permits Num, Lit, Var, BinOp, Unary, Ternary, Func {
    }

    record Num(double v) implements Node {
    }

    record Lit(String s) implements Node {
    }

    record Var(String name) implements Node {
    }

    record BinOp(Node l, String op, Node r) implements Node {
    }

    record Unary(String op, Node operand) implements Node {
    }

    record Ternary(Node cond, Node then, Node otherwise) implements Node {
    }

    record Func(String name, List<Node> args) implements Node {
    }

    // ================================================================== Tokenizer

    private enum Kind { NUM, STR, VAR, IDENT, OP, LPAREN, RPAREN, COMMA, EOF }

    private record Token(Kind kind, String text, int pos) {
    }

    private static final class Lexer {
        private final String src;
        private int i;

        Lexer(String src) {
            this.src = src;
        }

        List<Token> tokenize() {
            List<Token> out = new ArrayList<>();
            while (i < src.length()) {
                char c = src.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }
                int start = i;
                if (c == '{') {
                    // ${name} 形式：只取变量名
                    int end = src.indexOf('}', i);
                    if (end < 0) {
                        throw new ExprException(src, start, "变量缺少结束的 }");
                    }
                    String name = src.substring(i + 1, end).trim();
                    if (name.isEmpty()) {
                        throw new ExprException(src, start, "变量名为空");
                    }
                    out.add(new Token(Kind.VAR, name, start));
                    i = end + 1;
                    continue;
                }
                if (Character.isDigit(c) || (c == '.' && i + 1 < src.length()
                        && Character.isDigit(src.charAt(i + 1)))) {
                    while (i < src.length() && (Character.isDigit(src.charAt(i))
                            || src.charAt(i) == '.')) {
                        i++;
                    }
                    out.add(new Token(Kind.NUM, src.substring(start, i), start));
                    continue;
                }
                if (c == '"' || c == '\'') {
                    char q = c;
                    i++;
                    StringBuilder sb = new StringBuilder();
                    while (i < src.length() && src.charAt(i) != q) {
                        sb.append(src.charAt(i++));
                    }
                    if (i >= src.length()) {
                        throw new ExprException(src, start, "字符串缺少结束引号");
                    }
                    i++;
                    out.add(new Token(Kind.STR, sb.toString(), start));
                    continue;
                }
                if (Character.isLetter(c) || c == '_') {
                    while (i < src.length() && (Character.isLetterOrDigit(src.charAt(i))
                            || src.charAt(i) == '_' || src.charAt(i) == '.')) {
                        i++;
                    }
                    out.add(new Token(Kind.IDENT, src.substring(start, i), start));
                    continue;
                }
                if (c == '(') {
                    i++;
                    out.add(new Token(Kind.LPAREN, "(", start));
                    continue;
                }
                if (c == ')') {
                    i++;
                    out.add(new Token(Kind.RPAREN, ")", start));
                    continue;
                }
                if (c == ',') {
                    i++;
                    out.add(new Token(Kind.COMMA, ",", start));
                    continue;
                }
                String two = i + 1 < src.length() ? src.substring(i, i + 2) : "";
                if (two.equals(">=") || two.equals("<=") || two.equals("==")
                        || two.equals("!=") || two.equals("&&") || two.equals("||")) {
                    i += 2;
                    out.add(new Token(Kind.OP, two, start));
                    continue;
                }
                if ("+-*/%><!?:".indexOf(c) >= 0) {
                    i++;
                    out.add(new Token(Kind.OP, String.valueOf(c), start));
                    continue;
                }
                throw new ExprException(src, start, "无法识别的字符 '" + c + "'");
            }
            out.add(new Token(Kind.EOF, "", src.length()));
            return out;
        }
    }

    // ================================================================== Parser（递归下降）

    private static final class Parser {
        private final String src;
        private final List<Token> tokens;
        private int p;

        Parser(String src) {
            this.src = src;
            this.tokens = new Lexer(src).tokenize();
        }

        Node parseAll() {
            Node n = parseTernary();
            if (peek().kind() != Kind.EOF) {
                throw new ExprException(src, peek().pos(),
                        "多余的内容 '" + peek().text() + "'");
            }
            return n;
        }

        private Token peek() {
            return tokens.get(p);
        }

        private Token next() {
            return tokens.get(p++);
        }

        private boolean matchOp(String op) {
            Token t = peek();
            if (t.kind() == Kind.OP && t.text().equals(op)) {
                p++;
                return true;
            }
            return false;
        }

        Node parseTernary() {
            Node cond = parseOr();
            if (matchOp("?")) {
                Node t = parseTernary();
                if (!matchOp(":")) {
                    throw new ExprException(src, peek().pos(), "三元表达式缺少 ':'");
                }
                Node f = parseTernary();
                return new Ternary(cond, t, f);
            }
            return cond;
        }

        Node parseOr() {
            Node l = parseAnd();
            while (matchOp("||")) {
                l = new BinOp(l, "||", parseAnd());
            }
            return l;
        }

        Node parseAnd() {
            Node l = parseEquality();
            while (matchOp("&&")) {
                l = new BinOp(l, "&&", parseEquality());
            }
            return l;
        }

        Node parseEquality() {
            Node l = parseComparison();
            while (true) {
                if (matchOp("==")) {
                    l = new BinOp(l, "==", parseComparison());
                } else if (matchOp("!=")) {
                    l = new BinOp(l, "!=", parseComparison());
                } else {
                    return l;
                }
            }
        }

        Node parseComparison() {
            Node l = parseAdditive();
            while (true) {
                String op = null;
                if (matchOp(">=")) {
                    op = ">=";
                } else if (matchOp("<=")) {
                    op = "<=";
                } else if (matchOp(">")) {
                    op = ">";
                } else if (matchOp("<")) {
                    op = "<";
                }
                if (op == null) {
                    return l;
                }
                l = new BinOp(l, op, parseAdditive());
            }
        }

        Node parseAdditive() {
            Node l = parseMultiplicative();
            while (true) {
                if (matchOp("+")) {
                    l = new BinOp(l, "+", parseMultiplicative());
                } else if (matchOp("-")) {
                    l = new BinOp(l, "-", parseMultiplicative());
                } else {
                    return l;
                }
            }
        }

        Node parseMultiplicative() {
            Node l = parseUnary();
            while (true) {
                if (matchOp("*")) {
                    l = new BinOp(l, "*", parseUnary());
                } else if (matchOp("/")) {
                    l = new BinOp(l, "/", parseUnary());
                } else if (matchOp("%")) {
                    l = new BinOp(l, "%", parseUnary());
                } else {
                    return l;
                }
            }
        }

        Node parseUnary() {
            if (matchOp("!")) {
                return new Unary("!", parseUnary());
            }
            if (matchOp("-")) {
                return new Unary("-", parseUnary());
            }
            return parsePrimary();
        }

        Node parsePrimary() {
            Token t = peek();
            switch (t.kind()) {
                case NUM -> {
                    next();
                    return new Num(Double.parseDouble(t.text()));
                }
                case STR -> {
                    next();
                    return new Lit(t.text());
                }
                case VAR -> {
                    next();
                    return new Var(t.text());
                }
                case LPAREN -> {
                    next();
                    Node n = parseTernary();
                    if (next().kind() != Kind.RPAREN) {
                        throw new ExprException(src, t.pos(), "括号未闭合");
                    }
                    return n;
                }
                case IDENT -> {
                    next();
                    String name = t.text();
                    if (peek().kind() == Kind.LPAREN) {
                        next();
                        List<Node> args = new ArrayList<>();
                        if (peek().kind() != Kind.RPAREN) {
                            args.add(parseTernary());
                            while (peek().kind() == Kind.COMMA) {
                                next();
                                args.add(parseTernary());
                            }
                        }
                        if (next().kind() != Kind.RPAREN) {
                            throw new ExprException(src, t.pos(),
                                    "函数 " + name + " 的参数括号未闭合");
                        }
                        return new Func(name, args);
                    }
                    // 裸标识符当变量（支持 results.xxx.yyy 这种写法）
                    return new Var(name);
                }
                case EOF -> throw new ExprException(src, t.pos(), "表达式不完整");
                default -> throw new ExprException(src, t.pos(),
                        "意外的符号 '" + t.text() + "'");
            }
        }
    }
}
