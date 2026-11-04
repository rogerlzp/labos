package com.labos.kernel.persistence;

/**
 * 极简 JSON 构造（只用于拼 payload 写入 jsonb 列），避免引入第三方库。
 */
public final class Jsons {

    /** 已经是合法 JSON 的片段，嵌入时不再转义。 */
    public record Raw(String json) {
        public Raw {
            if (json == null) {
                json = "null";
            }
        }
    }

    private Jsons() {
    }

    /** 按 key, value, key, value 顺序拼对象；value 为 null 时输出 JSON null。 */
    public static String obj(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("参数必须成对出现");
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escape(String.valueOf(kv[i]))).append("\":");
            sb.append(literal(kv[i + 1]));
        }
        return sb.append('}').toString();
    }

    /** 拼接 JSON 数组；元素须已是合法 JSON 片段。 */
    public static String arr(java.util.List<String> items) {
        return items.stream().collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    /** 对象数组的便捷写法：直接传若干 {@link #obj(Object...)} 的结果。 */
    public static String arrOf(String... objs) {
        return "[" + String.join(",", objs) + "]";
    }

    /** 字符串字面量（用于手工构造数组元素）。 */
    public static String quote(String s) {
        return s == null ? "null" : literal(s);
    }

    private static String literal(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Raw r) {
            return r.json();
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        return '"' + escape(v.toString()) + '"';
    }

    public static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
