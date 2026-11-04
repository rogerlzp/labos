package com.labos.kernel.engine;

import java.util.Map;

/**
 * 条件表达式求值器（BPMN {@code conditionExpression} / 循环退出条件）。
 *
 * <p><b>为什么自己写而不用 eval</b>：表达式来自流程图的用户输入，用
 * {@code ScriptEngine} / {@code eval} 等于把进程交出去 —— 一条
 * {@code java.lang.Runtime.getRuntime().exec(...)} 就能打穿整台工控机。
 * 这里只认下面这套语法，多一个字符都报错。
 *
 * <p><b>语法</b>（够表达"温度到了没""板数够了没""这一圈走完了没"）：
 * <pre>
 *   expr    := or
 *   or      := and ('||' and)*
 *   and     := cmp ('&amp;&amp;' cmp)*
 *   cmp     := unary (('=='|'!='|'&gt;='|'&lt;='|'&gt;'|'&lt;') unary)?
 *   unary   := '!' unary | primary
 *   primary := number | string | 'true' | 'false' | '${变量名}' | '(' expr ')'
 * </pre>
 *
 * <p>类型规则（避免"3 &gt; 10"这种字符串比较的坑）：
 * <ul>
 *   <li>两边都像数字 → 按 double 比</li>
 *   <li>{@code ==}/{@code !=} 允许字符串/布尔比较</li>
 *   <li>大小比较两边都是数字才允许，否则报错（不猜）</li>
 *   <li>变量不存在 → 空串（判等成立、比大小报错），并在错误信息里点名</li>
 * </ul>
 */
public final class ConditionEvaluator {

    private ConditionEvaluator() {
    }

    /**
     * @param expr BPMN 里的表达式原文（可能带外层 {@code ${...}}）
     * @param vars 运行期变量
     * @return 结果是否成立
     * @throws IllegalArgumentException 语法错误 / 类型不匹配 / 变量缺失导致无法比较
     */
    public static boolean eval(String expr, Map<String, String> vars) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("空表达式");
        }
        String src = expr.trim();
        // 整条就是 ${x}（或 ${x} > 37 之外的纯变量）：当成布尔值用
        Parser p = new Parser(src, vars == null ? Map.of() : vars);
        Object v = p.parseExpression();
        p.expectEnd();
        return truthy(v);
    }

    /**
     * 求值并**返回值**（表达式节点用）。
     *
     * <p>{@link #eval} 只回答"成不成立"，而 `__expr` 节点要的是"算出来是多少"：
     * 把同一个表达式算成 {@link Double}/{@link String}/{@link Boolean}。
     */
    public static Object value(String expr, Map<String, String> vars) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("空表达式");
        }
        Parser p = new Parser(expr.trim(), vars == null ? Map.of() : vars);
        Object v = p.parseExpression();
        p.expectEnd();
        return v;
    }

    /**
     * **只验语法**，不求值（预检用）。
     *
     * <p>为什么不直接 eval：预检时变量还没有值，`${temp} > 37` 会因为
     * "空串不能比大小"被误判成错误。这里把变量统统当 0 代进去跑一遍 ——
     * 能抓出括号不配对、未知标识符、写了一半的表达式；抓不出的类型问题
     * 留给运行时（它会走 default 并留痕，不会卡死）。
     *
     * @throws IllegalArgumentException 语法有问题
     */
    public static void validate(String expr) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("空表达式");
        }
        java.util.Map<String, String> stub = new java.util.HashMap<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\$\\{([^}]+)\\}").matcher(expr);
        while (m.find()) {
            stub.put(m.group(1).trim(), "0");
        }
        eval(expr, stub);    // 结果不看，能跑通就说明语法没问题
    }

    static boolean truthy(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Double d) {
            return d != 0d;
        }
        String s = String.valueOf(v).trim();
        return !s.isEmpty() && !"0".equals(s) && !"false".equalsIgnoreCase(s);
    }

    // ------------------------------------------------------------------ 解析

    private static final class Parser {
        private final String s;
        private final Map<String, String> vars;
        private int i;

        Parser(String s, Map<String, String> vars) {
            this.s = s;
            this.vars = vars;
        }

        Object parseExpression() {
            return parseOr();
        }

        private Object parseOr() {
            Object left = parseAnd();
            while (match("||")) {
                Object right = parseAnd();
                left = truthy(left) || truthy(right);
            }
            return left;
        }

        private Object parseAnd() {
            Object left = parseCmp();
            while (match("&&")) {
                Object right = parseCmp();
                left = truthy(left) && truthy(right);
            }
            return left;
        }

        private Object parseCmp() {
            Object left = parseAdd();
            String op = peekOp();
            if (op == null) {
                return left;
            }
            i += op.length();
            Object right = parseAdd();
            return compare(left, op, right);
        }

        /** 加减：左结合。例：`${count} + 1 > 3`、`${a} - ${b} * 2 > 0`。 */
        private Object parseAdd() {
            Object left = parseMul();
            while (true) {
                skipWs();
                char c = peekChar();
                if (c != '+' && c != '-') {
                    return left;
                }
                i++;
                Object right = parseMul();
                left = arith(left, c, right);
            }
        }

        /** 乘除：优先级高于加减。 */
        private Object parseMul() {
            Object left = parseUnary();
            while (true) {
                skipWs();
                char c = peekChar();
                if (c != '*' && c != '/') {
                    return left;
                }
                i++;
                Object right = parseUnary();
                left = arith(left, c, right);
            }
        }

        private Object parseUnary() {
            skipWs();
            if (peekChar() == '!' && !startsWith("!=")) {
                i++;
                return !truthy(parseUnary());
            }
            // 一元负号：-${a} > -1。数字字面量走下面的 readNumber 也行，
            // 但 `-${a}` 必须在这里处理，否则 peekPrimary 会把它当减号。
            if (peekChar() == '-' && !Character.isDigit(peekCharAt(1))) {
                i++;
                Double v = asNumber(parseUnary());
                if (v == null) {
                    throw new IllegalArgumentException("负号后面不是数字");
                }
                return -v;
            }
            return parsePrimary();
        }

        private Object parsePrimary() {
            skipWs();
            if (i >= s.length()) {
                throw new IllegalArgumentException("表达式意外结束：" + s);
            }
            char c = s.charAt(i);
            if (c == '(') {
                i++;
                Object v = parseExpression();
                skipWs();
                if (i >= s.length() || s.charAt(i) != ')') {
                    throw new IllegalArgumentException("缺少右括号：" + s);
                }
                i++;
                return v;
            }
            if (c == '\'' || c == '"') {
                return readString(c);
            }
            if (s.startsWith("${", i)) {
                return readVar();
            }
            if (Character.isDigit(c) || c == '-' || c == '+') {
                return readNumber();
            }
            String word = readWord();
            if ("true".equalsIgnoreCase(word)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(word)) {
                return Boolean.FALSE;
            }
            if (word.isEmpty()) {
                throw new IllegalArgumentException("无法解析：" + s.substring(i));
            }
            throw new IllegalArgumentException("未知标识符 " + word + "（变量请写成 ${" + word + "}）");
        }

        private Object readString(char quote) {
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < s.length() && s.charAt(i) != quote) {
                sb.append(s.charAt(i++));
            }
            if (i >= s.length()) {
                throw new IllegalArgumentException("字符串没有闭合：" + s);
            }
            i++;
            return sb.toString();
        }

        private Object readVar() {
            i += 2;
            int start = i;
            while (i < s.length() && s.charAt(i) != '}') {
                i++;
            }
            if (i >= s.length()) {
                throw new IllegalArgumentException("变量没有闭合：" + s);
            }
            String name = s.substring(start, i).trim();
            i++;
            return vars.getOrDefault(name, "");
        }

        private Object readNumber() {
            int start = i;
            if (s.charAt(i) == '-' || s.charAt(i) == '+') {
                i++;
            }
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                i++;
            }
            String num = s.substring(start, i);
            try {
                return Double.parseDouble(num);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("不是合法数字：" + num);
            }
        }

        private String readWord() {
            int start = i;
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                i++;
            }
            return s.substring(start, i);
        }

        private String peekOp() {
            skipWs();
            for (String op : new String[]{"==", "!=", ">=", "<=", ">", "<"}) {
                if (s.startsWith(op, i)) {
                    return op;
                }
            }
            return null;
        }

        private boolean match(String op) {
            skipWs();
            if (s.startsWith(op, i)) {
                i += op.length();
                return true;
            }
            return false;
        }

        private boolean startsWith(String p) {
            return s.startsWith(p, i);
        }

        private char peekChar() {
            return i < s.length() ? s.charAt(i) : '\0';
        }

        private char peekCharAt(int offset) {
            int j = i + offset;
            return j < s.length() ? s.charAt(j) : '\0';
        }

        private void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        void expectEnd() {
            skipWs();
            if (i < s.length()) {
                throw new IllegalArgumentException("表达式多余内容：" + s.substring(i));
            }
        }
    }

    // ------------------------------------------------------------------ 比较

    /** 四则运算：两边都必须是数字，否则报错（不猜字符串拼接的语义）。 */
    private static Object arith(Object left, char op, Object right) {
        Double a = asNumber(left);
        Double b = asNumber(right);
        if (a == null || b == null) {
            throw new IllegalArgumentException(
                    "算术运算要求两边都是数字：" + left + " " + op + " " + right);
        }
        return switch (op) {
            case '+' -> a + b;
            case '-' -> a - b;
            case '*' -> a * b;
            case '/' -> {
                if (b == 0d) {
                    throw new IllegalArgumentException("除以 0");
                }
                yield a / b;
            }
            default -> throw new IllegalArgumentException("未知算术运算符 " + op);
        };
    }

    static Object compare(Object left, String op, Object right) {
        if ("==".equals(op)) {
            return eq(left, right);
        }
        if ("!=".equals(op)) {
            return !eq(left, right);
        }
        Double a = asNumber(left);
        Double b = asNumber(right);
        if (a == null || b == null) {
            throw new IllegalArgumentException(
                    "大小比较要求两边都是数字：" + left + " " + op + " " + right);
        }
        return switch (op) {
            case ">" -> a > b;
            case ">=" -> a >= b;
            case "<" -> a < b;
            case "<=" -> a <= b;
            default -> throw new IllegalArgumentException("未知运算符 " + op);
        };
    }

    private static boolean eq(Object left, Object right) {
        Double a = asNumber(left);
        Double b = asNumber(right);
        if (a != null && b != null) {
            return a.doubleValue() == b.doubleValue();
        }
        return String.valueOf(left).equals(String.valueOf(right));
    }

    /** 能当数字就当数字；不能就返回 null（**不抛异常**，交给调用方决定要不要比）。 */
    static Double asNumber(Object v) {
        if (v instanceof Double d) {
            return d;
        }
        if (v instanceof Boolean) {
            return null;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
