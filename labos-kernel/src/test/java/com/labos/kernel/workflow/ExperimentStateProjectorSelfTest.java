package com.labos.kernel.workflow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * S-03 单写者不变量 纯逻辑自测（不连库）：{@code experiment.status} 只允许
 * {@link ExperimentStateProjector} 写。
 *
 * <p>背景（状态机分层文档第 39 行问题陈述）：改动前 {@code experiment.status} 有 5 处
 * 分散的直接 UPDATE，各自拼 SQL；S-03 要求把这些写入「收口到唯一聚合器」，并把
 * {@code StatusVocabulary} 的 run→experiment CASE 回写也收口。**收口是"结构约束"，
 * 光靠人眼在评审时看一遍拦不住回退** —— 下一次谁图省事再写一条内联
 * {@code UPDATE experiment SET status} 就悄悄破了规矩。所以这里用源码扫描把它变成
 * 一条**会红的测试**。
 *
 * <p>扫描前先剥掉注释（行注释 / 块注释 / javadoc），只对"活代码"判定 —— 否则文档里
 * 提一句 {@code UPDATE experiment … SET status} 就会误伤。
 *
 * <p>钉死的三条：
 * <ol>
 *   <li><b>单写者</b>：{@code src/main/java} 下除 {@link ExperimentStateProjector} 外，
 *       任何文件都不得出现 {@code UPDATE experiment … SET status} 形态的 SQL
 *       （{@code UPDATE experiment_run / _batch / _process …} 不算；只改
 *       {@code priority/batch_split/version} 不算）；</li>
 *   <li><b>写入确实在这里</b>：{@link ExperimentStateProjector} 内该形态的 SQL 恰有 5 条
 *       （对应 5 个收口方法），防止有人把方法删空却让测试"变绿"；</li>
 *   <li><b>映射仍同源</b>：run→experiment 的映射仍由
 *       {@link StatusVocabulary#runToExperimentCaseSql()} 生成并在投影处使用
 *       —— 不许在投影里另手写一套 CASE（BUG-13/14 的根）。</li>
 * </ol>
 */
public final class ExperimentStateProjectorSelfTest {

    /** 单写者类文件名（与 {@link ExperimentStateProjector#SOLE_WRITER} 一致）。 */
    private static final String PROJECTOR_FILE = "ExperimentStateProjector.java";

    /**
     * {@code UPDATE experiment … SET status}：{@code experiment} 后必须是非单词字符
     * （空格/换行/别名 {@code e}），故 {@code UPDATE experiment_run/_batch/…} 天然不匹配。
     * 中间允许跨行（文本块），但限制在 300 字符内、不跨分号，避免把两条无关语句连起来看。
     */
    private static final Pattern WRITE_STATUS = Pattern.compile(
            "UPDATE\\s+experiment\\b[^;]{0,300}?SET\\s+status",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws IOException {
        Path srcRoot = findMainJavaRoot();
        if (srcRoot == null) {
            System.out.println("✗ 找不到 src/main/java（请在 labos-kernel 或仓库根目录下运行）");
            System.exit(1);
            return;
        }

        List<Path> files = javaFiles(srcRoot);

        section("1. 源码树可枚举");
        check("找到 src/main/java：" + srcRoot, true);
        check("扫描到 " + files.size() + " 个 .java 文件（>100 视为正常）",
                files.size() > 100);

        section("2. 单写者不变量：experiment.status 只由 ExperimentStateProjector 写");
        List<String> violationsOutside = new ArrayList<>();
        int hitsInsideProjector = 0;
        String projectorCode = null;
        for (Path f : files) {
            String code = stripComments(Files.readString(f, StandardCharsets.UTF_8));
            List<Integer> lines = matchingLines(code);
            if (lines.isEmpty()) {
                continue;
            }
            if (PROJECTOR_FILE.equals(f.getFileName().toString())) {
                hitsInsideProjector += lines.size();
                projectorCode = code;
                continue;
            }
            for (int ln : lines) {
                violationsOutside.add(relativize(f) + ":" + ln);
            }
        }
        eq("除单写者外 0 处 UPDATE experiment … SET status（违规点）",
                List.of(), violationsOutside);

        section("3. 写入确实收口在这里（防止空壳变绿）");
        eq("ExperimentStateProjector 内该形态 SQL 恰 5 条（对应 5 个收口方法）",
                5, hitsInsideProjector);
        eq("SOLE_WRITER 常量名", "ExperimentStateProjector",
                ExperimentStateProjector.SOLE_WRITER);
        check("单写者源码已被枚举（非 null）", projectorCode != null);

        section("4. run→experiment 映射仍同源（StatusVocabulary CASE 回写收口）");
        check("投影使用 StatusVocabulary.runToExperimentCaseSql()（不另手写 CASE）",
                projectorCode != null
                        && projectorCode.contains("StatusVocabulary.runToExperimentCaseSql()"));
        check("投影不出现手写 'WHEN r.status' CASE（映射只在词表里）",
                projectorCode != null && !projectorCode.contains("WHEN r.status"));
        check("runToExperimentCaseSql 在全量 main 源码中仍被引用",
                referencedAnywhere(files, "runToExperimentCaseSql"));

        System.out.printf("%n通过 %d, 失败 %d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ 注释剥离

    /**
     * 把 {@code //}、{@code /* *}{@code /}、javadoc 注释替换成空格（保留换行），
     * 保留字符串字面量与文本块内容 —— SQL 都在字符串/文本块里，注释里提一嘴不算数。
     */
    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int n = src.length();
        int i = 0;
        while (i < n) {
            char ch = src.charAt(i);
            // 文本块 """…"""
            if (ch == '"' && i + 2 < n && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                out.append("\"\"\"");
                i += 3;
                while (i < n) {
                    if (src.charAt(i) == '"' && i + 2 < n
                            && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                        out.append("\"\"\"");
                        i += 3;
                        break;
                    }
                    out.append(src.charAt(i++));
                }
                continue;
            }
            // 行注释 //
            if (ch == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
                continue;
            }
            // 块注释 /* … */
            if (ch == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                out.append("  ");
                i += 2;
                while (i < n && !(src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/')) {
                    out.append(src.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    out.append("  ");
                    i += 2;
                }
                continue;
            }
            // 普通字符串 "..."
            if (ch == '"') {
                out.append(ch);
                i++;
                while (i < n) {
                    char c = src.charAt(i);
                    out.append(c);
                    i++;
                    if (c == '\\' && i < n) {
                        out.append(src.charAt(i));
                        i++;
                    } else if (c == '"') {
                        break;
                    }
                }
                continue;
            }
            // 字符字面量 '...'
            if (ch == '\'') {
                out.append(ch);
                i++;
                while (i < n) {
                    char c = src.charAt(i);
                    out.append(c);
                    i++;
                    if (c == '\\' && i < n) {
                        out.append(src.charAt(i));
                        i++;
                    } else if (c == '\'') {
                        break;
                    }
                }
                continue;
            }
            out.append(ch);
            i++;
        }
        return out.toString();
    }

    // ------------------------------------------------------------ 扫描辅助

    /** 返回命中起点的 1-based 行号。 */
    private static List<Integer> matchingLines(String code) {
        List<Integer> lines = new ArrayList<>();
        Matcher m = WRITE_STATUS.matcher(code);
        while (m.find()) {
            int line = 1;
            for (int i = 0; i < m.start(); i++) {
                if (code.charAt(i) == '\n') {
                    line++;
                }
            }
            lines.add(line);
        }
        return lines;
    }

    private static boolean referencedAnywhere(List<Path> files, String token) throws IOException {
        for (Path f : files) {
            if (stripComments(Files.readString(f, StandardCharsets.UTF_8)).contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    private static String relativize(Path f) {
        try {
            return Path.of("").toAbsolutePath().relativize(f).toString();
        } catch (RuntimeException e) {
            return f.toString();
        }
    }

    /** 从 cwd 起向上找 {@code labos-kernel/src/main/java}（测试可能从两处 cwd 运行）。 */
    private static Path findMainJavaRoot() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++, p = p.getParent()) {
            Path cand = p.resolve("labos-kernel/src/main/java");
            if (Files.isDirectory(cand)) {
                return cand;
            }
            Path cand2 = p.resolve("src/main/java");
            if (Files.isDirectory(cand2) && Files.isDirectory(p.resolve("src/test/java"))) {
                return cand2;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 断言/输出

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

    private static void section(String title) {
        System.out.println("\n── " + title + " ──");
    }

    private ExperimentStateProjectorSelfTest() {
    }
}
