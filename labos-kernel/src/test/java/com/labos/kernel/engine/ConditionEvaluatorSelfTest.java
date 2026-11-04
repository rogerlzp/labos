package com.labos.kernel.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 条件表达式求值器自测（零依赖，javac 直接可跑）。
 *
 * <p>覆盖三类：
 * <ol>
 *   <li>正常求值：比较、逻辑、括号、变量、字符串</li>
 *   <li>类型规则：数字按数值比、字符串只判等、大小比较必须两边都是数字</li>
 *   <li>**拒绝执行**：语法错误/未知标识符/多余内容一律抛异常（宁可整条边不选，也不要猜）</li>
 * </ol>
 */
public final class ConditionEvaluatorSelfTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        section("1. 基本比较");
        ok("1 > 0", Map.of(), true);
        ok("3 >= 3", Map.of(), true);
        ok("2 < 1", Map.of(), false);
        ok("10 == 10", Map.of(), true);
        ok("10 != 10", Map.of(), false);
        // 关键：字符串 "10" 和 "9" 必须按数字比（9 < 10），不是字典序（"10" < "9"）
        ok("'10' > '9'", Map.of(), true);

        section("2. 变量");
        ok("${temp} > 37", vars("temp", "38"), true);
        ok("${temp} > 37", vars("temp", "37"), false);
        ok("${mode} == 'fast'", vars("mode", "fast"), true);
        ok("${mode} != 'fast'", vars("mode", "slow"), true);
        // 未定义变量按空串：判等能成立，比大小必须报错（不猜它是 0）
        ok("${missing} == ''", Map.of(), true);
        throwsOn("${missing} > 1", Map.of(), "未定义变量参与大小比较应报错");

        section("2.5 算术（v0.6 新增）");
        ok("1 + 1 == 2", Map.of(), true);
        ok("${count} + 1 > 3", vars("count", "3"), true);
        ok("${count} + 1 > 3", vars("count", "2"), false);
        // 优先级：乘除先算（2 + 3*2 > 7 成立）
        ok("2 + 3 * 2 > 7", Map.of(), true);
        ok("2 + 3 * 2 > 8", Map.of(), false);
        ok("(${a} + ${b}) / 2 >= 5", vars("a", "4", "b", "6"), true);
        ok("-${a} > -5", vars("a", "3"), true);
        throwsOn("${s} + 1 > 0", vars("s", "abc"), "字符串不能参与算术");
        throwsOn("1 / 0 > 0", Map.of(), "除以 0");

        section("3. 逻辑与括号");
        ok("(${a} > 1) && (${b} < 5)", vars("a", "2", "b", "3"), true);
        ok("(${a} > 1) && (${b} < 5)", vars("a", "2", "b", "9"), false);
        ok("${a} == 1 || ${b} == 2", vars("a", "1", "b", "0"), true);
        ok("!(${a} == 1)", vars("a", "1"), false);
        ok("true", Map.of(), true);
        ok("false", Map.of(), false);

        section("4. 拒绝执行（语法/未知标识符）");
        throwsOn("${a} > ", vars("a", "1"), "表达式不完整");
        throwsOn("(1 + 1", Map.of(), "缺右括号");
        throwsOn("foo > 1", Map.of(), "裸标识符（应写 ${foo}）");
        throwsOn("1 > 0 垃圾", Map.of(), "多余内容");
        // 危险内容不会被当成代码执行 —— 直接拒绝
        throwsOn("${a} == 'x' ; Runtime.getRuntime().exec('ls')", vars("a", "x"), "注入尝试");

        // 格式必须和 scripts/test-all.sh 的解析一致：`通过 N, 失败 M`
        System.out.printf("%n通过 %d, 失败 %d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 工具

    private static Map<String, String> vars(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static void ok(String expr, Map<String, String> vars, boolean expect) {
        try {
            boolean got = ConditionEvaluator.eval(expr, vars);
            check(expr, expect, got);
        } catch (RuntimeException e) {
            failed++;
            System.out.printf("  ✗ %-42s 期望 %s，实际抛异常：%s%n", expr, expect, e.getMessage());
        }
    }

    private static void throwsOn(String expr, Map<String, String> vars, String why) {
        try {
            boolean got = ConditionEvaluator.eval(expr, vars);
            failed++;
            System.out.printf("  ✗ %-42s 应该拒绝（%s），却返回 %s%n", expr, why, got);
        } catch (IllegalArgumentException e) {
            passed++;
            System.out.printf("  ✓ %-42s 拒绝：%s%n", expr, firstLine(e.getMessage()));
        } catch (RuntimeException e) {
            failed++;
            System.out.printf("  ✗ %-42s 抛了非 IllegalArgumentException：%s%n", expr, e);
        }
    }

    private static void check(String expr, boolean expect, boolean got) {
        if (expect == got) {
            passed++;
            System.out.printf("  ✓ %-42s = %s%n", expr, got);
        } else {
            failed++;
            System.out.printf("  ✗ %-42s 期望 %s 实际 %s%n", expr, expect, got);
        }
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        return i > 0 ? s.substring(0, i) : s;
    }

    private static void section(String title) {
        System.out.println("\n── " + title + " ──");
    }

    private ConditionEvaluatorSelfTest() {
    }
}
