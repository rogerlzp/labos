package com.labos.kernel.expr;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板：把字符串里的 <code>${...}</code> 求值并替换。
 *
 * <p><b>和 {@link Expression} 的区别</b>：
 * <ul>
 *   <li>{@code Expression} 解析**整个字符串是一条表达式**（如 {@code a + 1}）</li>
 *   <li>{@code Template} 处理**字符串里嵌了若干表达式**（如 {@code ID1183-L${i}}）</li>
 * </ul>
 *
 * <p>流程参数值用的是 Template，节点条件是 Expression。
 *
 * <p>找不到变量时**保留原样**（`${i}` 原样输出），
 * 这样日志里一眼能看出"这个变量没传"，而不是变成一个空串让人以为是空值。
 */
public final class Template {

    private static final Pattern SLOT = Pattern.compile("\\$\\{([^}]*)}");

    private Template() {
    }

    /** 是否含占位符。不含的话直接返回原串，不解析（省一次解析开销）。 */
    public static boolean hasSlot(String tpl) {
        return tpl != null && tpl.contains("${");
    }

    /**
     * 渲染模板。
     *
     * @param tpl  模板串，可以是纯文本（不含 {@code ${}}）
     * @param vars 变量表
     */
    public static String render(String tpl, Map<String, Object> vars) {
        if (tpl == null || !hasSlot(tpl)) {
            return tpl;
        }
        Matcher m = SLOT.matcher(tpl);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String body = m.group(1).trim();
            String replacement;
            try {
                Object v = Expression.parse(body).eval(vars);
                replacement = Values.toText(v);
            } catch (ExprException e) {
                // 变量未定义时保留原样：日志里看到 ${i} 就知道没传
                if (e.getMessage() != null && e.getMessage().contains("变量未定义")) {
                    replacement = m.group(0);
                } else {
                    throw e;
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
