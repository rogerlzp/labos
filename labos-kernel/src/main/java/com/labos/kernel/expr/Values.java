package com.labos.kernel.expr;

import java.util.Map;

/**
 * 值的类型转换。表达式里只有"数字"和"字符串"两种标量。
 *
 * <p>布尔用 double 的 1/0 表示不方便比较，这里统一用 {@link Boolean}
 * 并在输出时转成 "true"/"false"。
 */
final class Values {

    private Values() {
    }

    /** 数值化。字符串尝试 parse，失败抛异常（不静默当 0）。 */
    static double toNum(Object v, String expr) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                throw new ExprException(expr, -1,
                        "不能把字符串 \"" + s + "\" 当数字用");
            }
        }
        if (v == null) {
            throw new ExprException(expr, -1, "对 null 做数值运算");
        }
        throw new ExprException(expr, -1,
                "不支持的类型参与运算：" + v.getClass().getSimpleName());
    }

    /**
     * 能不能当数字用。
     *
     * <p><b>为什么需要</b>：流程变量都存在 String 里（{@code i="3"}），
     * 如果不判断就直接 {@code +}，会拼成 "31" 而不是 4。
     */
    static boolean isNumeric(Object v) {
        if (v instanceof Number) {
            return true;
        }
        if (v instanceof String s) {
            try {
                Double.parseDouble(s.trim());
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    /** 布尔化。数字非 0 为真，字符串非空为真。 */
    static boolean toBool(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (v instanceof String s) {
            return !s.isEmpty() && !"false".equalsIgnoreCase(s);
        }
        return v != null;
    }

    /**
     * 输出成文本。整数不显示小数点——点位编号拼接时不希望出现 "L3.0"。
     */
    static String toText(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (v instanceof Double d) {
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                return String.valueOf(d.longValue());
            }
            // 去掉尾随 0：0.30000000000000004 → 0.3
            String s = String.format("%.10f", d);
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) {
                s = s.substring(0, s.length() - 1);
            }
            return s;
        }
        if (v instanceof Number n) {
            return toText(n.doubleValue());
        }
        return String.valueOf(v);
    }

    /** 两个值是否相等（数字按数值比，其余按字符串比）。 */
    static boolean equal(Object a, Object b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return toText(a).equals(toText(b));
    }

    /** 查变量：支持 a.b.c 这种路径，也支持键名本身就是 a.b.c 的扁平存储。 */
    static Object lookup(Map<String, Object> vars, String path) {
        if (vars.containsKey(path)) {
            return vars.get(path);
        }
        String[] parts = path.split("\\.");
        if (parts.length == 1) {
            return null;
        }
        Object cur = vars.get(parts[0]);
        for (int i = 1; i < parts.length && cur != null; i++) {
            if (cur instanceof Map<?, ?> m) {
                cur = m.get(parts[i]);
            } else {
                return null;
            }
        }
        return cur;
    }
}
