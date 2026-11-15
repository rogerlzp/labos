package com.labos.kernel.driver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * P2 阶段 A · 统一物理命令派发门禁 源码不变量（纯逻辑，不连库）。
 *
 * <p>评审意见 #1：受保护 Transfer 的主机械臂动作曾直接
 * {@code driver.execute(buildCommand(...))}，绕过 {@code executeCommand}
 * （CommandLedger.begin + 幂等 + 中止契约）。把「所有物理下发必须经
 * {@link DriverStepExecutor#executeCommand}」钉成一条会红的源码扫描测试，
 * 防止回退。
 *
 * <p>钉死两条：
 * <ol>
 *   <li>不存在 {@code driver.execute(buildCommand} 旁路形态（正确形态是
 *       {@code executeCommand(driver, ctx, buildCommand(...))}）；</li>
 *   <li>{@code DriverStepExecutor.java} 内所有 {@code driver.execute(} 只允许落在
 *       {@code doExecuteCommand} 方法体内（executeCommand 的唯一物理下发点）。</li>
 * </ol>
 */
public final class DriverDispatchPathSelfTest {

    private static final String TARGET = "DriverStepExecutor.java";
    private static final String DISPATCH_METHOD = "doExecuteCommand";

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws IOException {
        Path f = findFile();
        if (f == null) {
            System.out.println("✗ 找不到 " + TARGET + "（请在 labos-kernel 或仓库根目录下运行）");
            System.exit(1);
            return;
        }
        String code = Files.readString(f, StandardCharsets.UTF_8);

        section("1. 旁路形态 driver.execute(buildCommand 已消除");
        eq(TARGET + " 不含 driver.execute(buildCommand", false,
                code.contains("driver.execute(buildCommand"));

        section("2. 所有 driver.execute( 只允许落在 doExecuteCommand 方法体内");
        List<Integer> total = occurrences(code, "driver.execute(");
        List<Integer> inDispatch = occurrencesInMethod(code, "driver.execute(",
                DISPATCH_METHOD);
        check(TARGET + " 有 driver.execute( 调用（" + total.size() + " 处）", !total.isEmpty());
        eq("全部落在 " + DISPATCH_METHOD + " 内", total, inDispatch);

        System.out.printf("%n通过 %d, 失败 %d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 从 cwd 起向上找 {@code DriverStepExecutor.java}。 */
    private static Path findFile() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++, p = p.getParent()) {
            Path cand = p.resolve("labos-kernel/src/main/java/com/labos/kernel/driver/"
                    + TARGET);
            if (Files.isRegularFile(cand)) {
                return cand;
            }
            Path cand2 = p.resolve("src/main/java/com/labos/kernel/driver/" + TARGET);
            if (Files.isRegularFile(cand2)) {
                return cand2;
            }
        }
        return null;
    }

    /** 所有命中的 1-based 行号（不去注释，旁路注释里提一嘴也算危险信号）。 */
    private static List<Integer> occurrences(String code, String token) {
        List<Integer> lines = new ArrayList<>();
        int idx = 0;
        while ((idx = code.indexOf(token, idx)) >= 0) {
            lines.add(lineOf(code, idx));
            idx += token.length();
        }
        return lines;
    }

    /** 命中且位于指定方法声明到下一处方法/构造/类结束之间的行号。 */
    private static List<Integer> occurrencesInMethod(String code, String token,
                                                     String methodName) {
        // 声明在源码里位于调用之后，取最后一处即方法声明；
        // （首处是 executeCommand 内的调用，其后还有 catch 块 '{'，会定位错方法体）。
        int start = code.lastIndexOf(methodName + "(");
        if (start < 0) {
            return List.of();
        }
        // 方法体起点：声明行的下一个 '{'
        int bodyOpen = code.indexOf('{', start);
        if (bodyOpen < 0) {
            return List.of();
        }
        int end = endOfMethod(code, bodyOpen);
        List<Integer> lines = new ArrayList<>();
        int idx = bodyOpen;
        while ((idx = code.indexOf(token, idx)) >= 0 && idx < end) {
            lines.add(lineOf(code, idx));
            idx += token.length();
        }
        return lines;
    }

    /** 从方法体 '{' 起做括号配对，返回方法体结束位置。 */
    private static int endOfMethod(String code, int bodyOpen) {
        int depth = 0;
        boolean inStr = false;
        boolean inChr = false;
        for (int i = bodyOpen; i < code.length(); i++) {
            char c = code.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (inChr) {
                if (c == '\\') {
                    i++;
                } else if (c == '\'') {
                    inChr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '\'') {
                inChr = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return code.length();
    }

    private static int lineOf(String code, int idx) {
        int line = 1;
        for (int i = 0; i < idx; i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static void section(String title) {
        System.out.println("\n── " + title + " ──");
    }

    private static void eq(String name, Object expect, Object got) {
        check(name, Objects.equals(expect, got), "期望 " + expect + "，实际 " + got);
    }

    private static void check(String name, boolean ok) {
        check(name, ok, null);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.printf("  ✓ %s%n", name);
        } else {
            failed++;
            System.out.printf("  ✗ %s%s%n", name, detail == null ? "" : "（" + detail + "）");
        }
    }

    private DriverDispatchPathSelfTest() {
    }
}
