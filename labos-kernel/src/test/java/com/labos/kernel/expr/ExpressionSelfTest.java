package com.labos.kernel.expr;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 表达式引擎单测。16 个用例覆盖设计文档第八节。
 *
 * <p>重点不是"能算对"，是"错的时候消息对不对"——
 * 错误的表达式在生产里会让人排查半天，消息必须指出是哪个表达式、哪一位。
 */
public final class ExpressionSelfTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        // ---- 正常求值 ----
        eq("1 + 2", null, "3");
        eq("i", vars("i", 5), "5");
        eq("i + 1", vars("i", 5), "6");
        eqTemplate("ID1183-L${i}", vars("i", 3), "ID1183-L3");
        eqTemplate("COLD-${id}", vars("id", "P1"), "COLD-P1");
        eqTemplate("no-slot", null, "no-slot");
        eqTemplate("${unknown}", null, "${unknown}");
        eq("floor(7 / 2)", null, "3");
        eq("7 % 3", null, "1");
        eq("max(a, b)", vars("a", 3, "b", 7), "7");
        eq("a > b ? 1 : 2", vars("a", 5, "b", 3), "1");
        eq("a > 0 && b < 10", vars("a", 1, "b", 5), "true");
        eq("!flag", vars("flag", false), "true");
        eq("(a + b) * 2", vars("a", 1, "b", 2), "6");

        // ---- 变量是字符串时也要能做算术（流程变量的真实情况）----
        eq("i + 1", vars("i", "5"), "6");
        eq("i * 2", vars("i", "5"), "10");
        eq("floor(usedSlots / 1) + 1", vars("usedSlots", "3"), "4");

        // ---- 字符串拼接 ----
        eq("'COLD-' + id", vars("id", "P1"), "COLD-P1");
        eq("'a' + 'b'", null, "ab");

        // ---- 嵌套路径（results.readPlate.od）----
        eq("results.readPlate.od", flat("results.readPlate.od", 0.65), "0.65");
        eq("results.readPlate.od > 0.5",
                flat("results.readPlate.od", 0.65), "true");

        // ---- has() ----
        eq("has('x')", vars("x", 1), "true");
        eq("has('y')", vars("x", 1), "false");
        eq("has('readPlate')", flat("readPlate", 1), "true");

        // ---- 除零 / 未定义 / 语法错 / 类型错，都要抛且消息带原文 ----
        throwsWith("1 / 0", null, "除数为 0", "1 / 0");
        throwsWith("undefinedVar", null, "变量未定义", "undefinedVar");
        throwsWith("1 +", null, "表达式不完整", "1 +");
        throwsWith("'abc' - 1", null, "不能对字符串做", "'abc' - 1");
        throwsWith("noSuchFunc(1)", null, "未知函数", "noSuchFunc(1)");
        throwsWith("(1 + 2", null, "括号未闭合", "(1 + 2");

        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void eqTemplate(String tpl, Map<String, Object> vars, String expect) {
        try {
            String actual = Template.render(tpl, vars == null ? Map.of() : vars);
            check("模板 " + tpl, expect, actual);
        } catch (RuntimeException e) {
            fail++;
            System.out.println("  ✗ 模板 " + tpl + " 抛异常：" + e.getMessage());
        }
    }

    private static Map<String, Object> vars(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> flat(String k, Object v) {
        return vars(k, v);
    }

    private static void eq(String expr, Map<String, Object> vars, String expect) {
        try {
            String actual = Expression.parse(expr)
                    .evalString(vars == null ? Map.of() : vars);
            check(expr, expect, actual);
        } catch (RuntimeException e) {
            fail++;
            System.out.println("  ✗ " + expr + " 抛异常：" + e.getMessage());
        }
    }

    private static void throwsWith(String expr, Map<String, Object> vars,
                                   String expectSubstring, String expectSource) {
        try {
            Expression.parse(expr).eval(vars == null ? Map.of() : vars);
            fail++;
            System.out.println("  ✗ " + expr + " 应当抛异常但没有");
        } catch (ExprException e) {
            String msg = e.getMessage();
            boolean okDetail = msg.contains(expectSubstring);
            boolean okSource = msg.contains(expectSource);
            if (okDetail && okSource) {
                pass++;
                System.out.println("  ✓ " + expr + " → " + msg);
            } else {
                fail++;
                System.out.println("  ✗ " + expr + " 消息不合格：" + msg);
            }
        }
    }

    private static void check(String expr, String expect, String actual) {
        if (expect.equals(actual)) {
            pass++;
            System.out.println("  ✓ " + expr + " = " + actual);
        } else {
            fail++;
            System.out.println("  ✗ " + expr + " 期望 " + expect + " 实际 " + actual);
        }
    }
}
