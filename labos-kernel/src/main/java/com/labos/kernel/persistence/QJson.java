package com.labos.kernel.persistence;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

/**
 * 只读 JSON/CSV 组装的共享静态助手：从 {@code QueryService} 拆出，语义逐字保留
 * （m 前缀 = Map 版，null 处理不变）。
 *
 * <p>原先私有散在 2800 行的 {@code QueryService} 里，按域拆 Service 时
 * 每个域都要用同一套格式化，单独立一个工具类，避免复制八份。
 */
public final class QJson {

    private QJson() {
    }

    public static String csv(String s) {
        if (s == null) {
            return "";
        }
        return s.contains(",") || s.contains("\"")
                ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    /** Map 版 num：numeric 列可能是 BigDecimal / Double，统一按 BigDecimal 格式化。 */
    public static String mnum(Object o) {
        if (o == null) {
            return "";
        }
        if (o instanceof java.math.BigDecimal b) {
            return b.stripTrailingZeros().toPlainString();
        }
        if (o instanceof Number n) {
            return new java.math.BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        }
        return o.toString();
    }

    /** Map 版 csv：null 与 csv(String) 一样输出空串。 */
    public static String mcsv(Object o) {
        return csv(o == null ? null : String.valueOf(o));
    }

    public static String num(java.math.BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    public static String unq(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
            t = t.substring(1, t.length() - 1).replace("\"\"", "\"");
        }
        return t;
    }

    public static java.math.BigDecimal dec(String[] f, int i) {
        if (f.length <= i || f[i] == null || f[i].isBlank()) {
            return null;
        }
        try {
            return new java.math.BigDecimal(unq(f[i]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Double pick(String json, String... names) {
        for (String n : names) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"" + java.util.regex.Pattern.quote(n)
                            + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
                    .matcher(json);
            if (m.find()) {
                return Double.parseDouble(m.group(1));
            }
        }
        return null;
    }

    /**
     * 从机械臂返回原文里提取坐标。
     *
     * <p>支持的字段名：x/y/z、pos_x/pos_y/pos_z、X/Y/Z。
     * 找到就返回数组 [x,y,z]（缺的为 null），找不到返回 null。
     *
     * <p>为什么用宽松的多字段匹配：不同厂商的驱动返回格式不一样，
     * 有的叫 `x`，有的叫 `pos_x`，有的嵌在 `data` 里。
     * 硬编码一种会导致"某家驱动永远读不到"。
     */
    public static Double[] extractXYZ(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Double x = pick(raw, "x", "pos_x", "X");
        Double y = pick(raw, "y", "pos_y", "Y");
        Double z = pick(raw, "z", "pos_z", "Z");
        return (x == null && y == null && z == null) ? null : new Double[]{x, y, z};
    }

    /** 把已序列化好的 JSON 片段原样嵌入（避免二次转义）。 */
    public static Jsons.Raw raw(String json) {
        return new Jsons.Raw(json == null ? "{}" : json);
    }

    /** Map 版 {@link #raw(String)}：null 仍兜成 {}。 */
    public static Jsons.Raw mraw(java.util.Map<String, Object> row, String key) {
        Object o = row.get(key);
        return new Jsons.Raw(o == null ? "{}" : String.valueOf(o));
    }

    /** Map 里的时间列转 Instant（MyBatis 对 timestamptz 给 java.sql.Timestamp）。 */
    public static java.time.Instant minst(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof java.sql.Timestamp t) {
            return t.toInstant();
        }
        if (o instanceof java.time.Instant i) {
            return i;
        }
        if (o instanceof java.time.OffsetDateTime d) {
            return d.toInstant();
        }
        return java.time.Instant.parse(o.toString());
    }

    /** Map 版 {@link #str(ResultSet, String)}：null 保留 null。 */
    public static String mstr(java.util.Map<String, Object> row, String key) {
        Object o = row.get(key);
        return o == null ? null : o.toString();
    }

    /** Map 版 rs.getInt：null 当 0。 */
    public static int mint(java.util.Map<String, Object> row, String key) {
        Object o = row.get(key);
        return o instanceof Number n ? n.intValue() : 0;
    }

    public static String str(ResultSet rs, String col) throws java.sql.SQLException {
        Object o = rs.getObject(col);
        return o == null ? null : o.toString();
    }

    public static String shortVersion(String v) {
        if (v == null) {
            return "";
        }
        int i = v.indexOf(" on ");
        return i > 0 ? v.substring(0, i) : v;
    }

    /** 从图 XML 里抠某个节点元素上的 resource="…"。 */
    public static Long resourceOf(String xml, String nodeKey) {
        if (xml == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<[a-zA-Z0-9_:]+[^>]*\\bid=\"" + java.util.regex.Pattern.quote(nodeKey)
                        + "\"[^>]*>")
                .matcher(xml);
        if (!m.find()) {
            return null;
        }
        java.util.regex.Matcher r = java.util.regex.Pattern
                .compile("\\sresource=\"(\\d+)\"").matcher(m.group());
        return r.find() ? Long.parseLong(r.group(1)) : null;
    }

    public static Map<?, ?> asMap(Object value, String what) {
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new IllegalArgumentException(what + " 不是对象");
    }

    public static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("workflowId 不是合法数字：" + value);
        }
    }

    public static Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return new MiniJson(json).parseValue();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("画布 JSON 解析失败：" + e.getMessage(), e);
        }
    }

    private static final class MiniJson {
        private final String s;
        private int i;

        MiniJson(String s) {
            this.s = s;
        }

        Object parseValue() {
            skip();
            if (i >= s.length()) {
                return null;
            }
            char c = s.charAt(i);
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"') {
                return parseString();
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return true;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return false;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            return parseNumberOrToken();
        }

        private Map<String, Object> parseObject() {
            expect('{');
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            skip();
            if (peek('}')) {
                expect('}');
                return out;
            }
            while (true) {
                String key = parseString();
                skip();
                expect(':');
                out.put(key, parseValue());
                skip();
                if (peek('}')) {
                    expect('}');
                    return out;
                }
                expect(',');
            }
        }

        private java.util.List<Object> parseArray() {
            expect('[');
            java.util.List<Object> out = new java.util.ArrayList<>();
            skip();
            if (peek(']')) {
                expect(']');
                return out;
            }
            while (true) {
                out.add(parseValue());
                skip();
                if (peek(']')) {
                    expect(']');
                    return out;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (i >= s.length()) {
                        break;
                    }
                    char esc = s.charAt(i++);
                    switch (esc) {
                        case '"', '\\', '/' -> sb.append(esc);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) {
                                throw new IllegalArgumentException("非法 unicode 转义");
                            }
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> throw new IllegalArgumentException("未知转义：" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("字符串未闭合");
        }

        private Object parseNumberOrToken() {
            int j = i;
            while (j < s.length() && ",]} \n\r\t".indexOf(s.charAt(j)) < 0) {
                j++;
            }
            String token = s.substring(i, j);
            i = j;
            try {
                if (token.contains(".") || token.contains("e") || token.contains("E")) {
                    return Double.parseDouble(token);
                }
                return Long.parseLong(token);
            } catch (NumberFormatException e) {
                return token;
            }
        }

        private void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private boolean peek(char c) {
            skip();
            return i < s.length() && s.charAt(i) == c;
        }

        private void expect(char c) {
            skip();
            if (i >= s.length() || s.charAt(i) != c) {
                throw new IllegalArgumentException("期待 '" + c + "'");
            }
            i++;
        }
    }
}
