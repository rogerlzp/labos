package com.labos.kernel.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 状态词表 纯逻辑自测（不连库）。
 *
 * <p>锁住 P2「状态映射收口」的四条不能退的语义：
 * <ol>
 *   <li><b>契约形状</b>：五个 scope 的 code 顺序/数量固定（顺序即展示序）；
 *       每个状态的 label 非空、color 在允许令牌集合里、terminal/active 集合明确；</li>
 *   <li><b>映射唯一</b>：run→experiment 的 8 条映射（completed→finished 等）被钉死，
 *       前端消费的 transitions 与后端写库的 CASE 来自同一张表；</li>
 *   <li><b>写入行为不变</b>：{@link StatusVocabulary#runToExperimentCaseSql()} 的精确文本
 *       被钉死；且对全部 11 个 run 状态 + 未知码模拟求值，必须与改前规则
 *       「completed→finished，failed→failed，running→running，其余原样」逐状态相等——
 *       这是"重构不许改行为"的底线，不是口味问题；</li>
 *   <li><b>JSON 契约</b>：{@code GET /api/meta/status-vocabulary} 的顶层键、嵌套键、
 *       元素字段名/类型与契约一致（前端不再各写一份词表，形状漂移会静默退化成 fallback）。</li>
 * </ol>
 */
public final class StatusVocabularySelfTest {

    /** 颜色令牌允许集合（契约：green/red/orange/gold/blue/default/processing/volcano）。 */
    private static final Set<String> ALLOWED_COLORS = Set.of(
            "default", "green", "red", "orange", "gold", "blue", "processing", "volcano");

    /** 各 scope 契约顺序（顺序即展示序，改它等于改 UI）。 */
    private static final Map<String, List<String>> EXPECTED_CODES = Map.of(
            "run", List.of("draft", "pending", "planned", "queued", "running", "paused",
                    "completed", "failed", "aborted", "cancelled", "skipped"),
            "node", List.of("pending", "ready", "running", "completed", "failed", "manual",
                    "skipped", "aborted", "bypassed"),
            "step", List.of("pending", "running", "completed", "failed", "skipped", "paused"),
            "experiment", List.of("created", "planned", "queued", "running", "paused",
                    "completed", "finished", "failed", "aborted", "cancelled", "skipped",
                    "deleted"),
            "batch", List.of("pending", "queued", "running", "paused", "completed", "failed",
                    "aborted"));

    private static final Map<String, List<String>> EXPECTED_TERMINAL = Map.of(
            "run", List.of("completed", "failed", "aborted", "cancelled", "skipped"),
            "node", List.of("completed", "failed", "skipped", "aborted", "bypassed"),
            "step", List.of("completed", "failed", "skipped"),
            "experiment", List.of("completed", "finished", "failed", "aborted", "cancelled",
                    "skipped", "deleted"),
            "batch", List.of("completed", "failed", "aborted"));

    private static final Map<String, List<String>> EXPECTED_ACTIVE = Map.of(
            "run", List.of("queued", "running", "paused"),
            "node", List.of("running", "manual"),
            "step", List.of("running", "paused"),
            "experiment", List.of("queued", "running", "paused"),
            "batch", List.of("queued", "running", "paused"));

    private static final Pattern CASE_WHEN = Pattern.compile(
            "WHEN r\\.status = '([^']*)' THEN '([^']*)'");

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        section("1. 契约版本与 scope 集");
        eq("version = 2026-11-03", "2026-11-03", StatusVocabulary.VERSION);
        eq("scope 顺序", List.of("run", "node", "step", "experiment", "batch"),
                StatusVocabulary.scopes());

        section("2. 各 scope 的 code 顺序与数量（顺序即展示序）");
        eq("五个 scope 的状态码/顺序/数量", EXPECTED_CODES, codesByScope());

        section("3. 文案/颜色/terminal/active 合法性");
        check("label 全部非空且 color 在允许令牌集合内", labelsAndColorsOk());
        eq("terminal 集合", EXPECTED_TERMINAL, flagByScope(true));
        eq("active 集合", EXPECTED_ACTIVE, flagByScope(false));

        section("4. 查询函数（前端与兜底口径）");
        StatusVocabulary.StatusDef completed =
                StatusVocabulary.find("run", "completed");
        eq("find 已知字段 + 未知 code/scope 兜底",
                List.of("已完成|green|true|false", "null", "null", "empty",
                        "no_such_code", "default"),
                List.of(
                        completed == null ? "null"
                                : completed.label() + "|" + completed.color() + "|"
                                        + completed.terminal() + "|" + completed.active(),
                        String.valueOf(StatusVocabulary.find("run", "no_such_code")),
                        String.valueOf(StatusVocabulary.find("no_such_scope", "running")),
                        StatusVocabulary.statuses("no_such_scope").isEmpty()
                                ? "empty" : "not-empty",
                        StatusVocabulary.labelOf("run", "no_such_code"),
                        StatusVocabulary.colorOf("run", "no_such_code")));
        eq("isTerminal/isActive 抽样（未知码一律 false）",
                List.of(true, false, true, true, false,
                        true, true, true, false, false),
                List.of(
                        StatusVocabulary.isTerminal("run", "completed"),
                        StatusVocabulary.isTerminal("run", "running"),
                        StatusVocabulary.isTerminal("experiment", "finished"),
                        StatusVocabulary.isTerminal("node", "bypassed"),
                        StatusVocabulary.isTerminal("run", "no_such_code"),
                        StatusVocabulary.isActive("run", "paused"),
                        StatusVocabulary.isActive("node", "manual"),
                        StatusVocabulary.isActive("experiment", "queued"),
                        StatusVocabulary.isActive("run", "completed"),
                        StatusVocabulary.isActive("run", "no_such_code")));

        section("5. run → experiment 映射（transitions 契约）");
        Map<String, String> map = StatusVocabulary.runToExperiment();
        List<String> entries = new ArrayList<>();
        map.forEach((from, to) -> entries.add(from + "->" + to));
        eq("8 条映射、顺序 = 契约顺序（completed 必须映射成 finished）",
                List.of("completed->finished", "failed->failed", "running->running",
                        "paused->paused", "aborted->aborted", "cancelled->cancelled",
                        "skipped->skipped", "queued->queued"),
                entries);

        section("6. 生成的 SQL CASE（写入侧唯一事实来源 + 行为等价）");
        String expectedCase = "CASE"
                + " WHEN r.status = 'completed' THEN 'finished'"
                + " WHEN r.status = 'failed' THEN 'failed'"
                + " WHEN r.status = 'running' THEN 'running'"
                + " WHEN r.status = 'paused' THEN 'paused'"
                + " WHEN r.status = 'aborted' THEN 'aborted'"
                + " WHEN r.status = 'cancelled' THEN 'cancelled'"
                + " WHEN r.status = 'skipped' THEN 'skipped'"
                + " WHEN r.status = 'queued' THEN 'queued'"
                + " ELSE r.status END";
        String sql = StatusVocabulary.runToExperimentCaseSql();
        eq("CASE 精确文本（顺序 = 映射迭代序）", expectedCase, sql);
        check("CASE 形状：8 个 WHEN / 单引号成对 / ELSE r.status 兜底",
                countOccurrences(sql, " WHEN ") == map.size()
                        && sql.startsWith("CASE WHEN r.status = ")
                        && sql.endsWith(" ELSE r.status END")
                        && countOccurrences(sql, "'") % 2 == 0);

        // 行为等价：直接用生成的 SQL 片段（按 SQL CASE 语义：首个匹配生效，否则 ELSE）
        // 对全部 run 状态求值，与改前的三行 CASE 规则逐状态比对。
        List<String> mismatch = new ArrayList<>();
        List<String> probe = new ArrayList<>(codes("run"));
        probe.add("prior_unknown_status");
        for (String s : probe) {
            String got = evaluateGeneratedCase(sql, s);
            String baseline = baselineCase(s);
            if (!Objects.equals(baseline, got)) {
                mismatch.add(s + ": 改前=" + baseline + ", 生成=" + got);
            }
        }
        eq("全部 11 个 run 状态 + 未知码求值 = 改前规则", List.of(), mismatch);

        section("7. pauseKinds / holdKinds / holdScopes");
        eq("pauseKinds 3 个、顺序/文案/颜色/auto/hint 固定",
                List.of("paused_fault|设备故障|red|true|hint-ok",
                        "waiting_human|等待人工|orange|false|hint-ok",
                        "paused_unknown|板位未知|volcano|false|hint-ok"),
                pauseKindFacts());
        eq("holdKinds 4 个", List.of(
                        "fault|设备故障|red",
                        "plate_unknown|板位未知|volcano",
                        "human|等人工确认|orange",
                        "manual|人工暂停|orange"),
                holdKindFacts());
        eq("holdScopes 3 个", List.of("site|全场", "island|岛", "experiment|本实验"),
                holdScopeFacts());

        section("8. 契约 JSON（ApiServer 原样 send）");
        String json = StatusVocabulary.toJson();
        List<String> missing = new ArrayList<>();
        for (String key : List.of("\"version\"", "\"scopes\"", "\"run\"", "\"node\"",
                "\"step\"", "\"experiment\"", "\"batch\"", "\"pauseKinds\"",
                "\"holdKinds\"", "\"holdScopes\"", "\"transitions\"",
                "\"runToExperiment\"")) {
            if (!json.contains(key)) {
                missing.add(key);
            }
        }
        eq("顶层/嵌套 key 齐全", List.of(), missing);
        check("transitions 完整 8 条且顺序与映射一致",
                json.contains("\"runToExperiment\":{\"completed\":\"finished\","
                        + "\"failed\":\"failed\",\"running\":\"running\","
                        + "\"paused\":\"paused\",\"aborted\":\"aborted\","
                        + "\"cancelled\":\"cancelled\",\"skipped\":\"skipped\","
                        + "\"queued\":\"queued\"}"));
        check("状态元素字段形状（code/label/color/terminal/active）",
                json.contains("{\"code\":\"draft\",\"label\":\"草稿\","
                        + "\"color\":\"default\",\"terminal\":false,\"active\":false}")
                        && json.contains("{\"code\":\"running\",\"label\":\"执行中\","
                        + "\"color\":\"processing\",\"terminal\":false,\"active\":true}")
                        && json.contains("{\"code\":\"skipped\",\"label\":\"已跳过\","
                        + "\"color\":\"gold\",\"terminal\":true,\"active\":false}"));
        check("pauseKinds/holdKinds/holdScopes 字段形状",
                json.contains("{\"code\":\"paused_fault\",\"label\":\"设备故障\","
                        + "\"color\":\"red\",\"auto\":true,\"hint\":\"")
                        && json.contains("{\"code\":\"fault\",\"label\":\"设备故障\","
                        + "\"color\":\"red\"}")
                        && json.contains("{\"code\":\"site\",\"label\":\"全场\"}"));
        check("JSON 括号成对（粗糙结构体检）",
                json.startsWith("{") && json.endsWith("}")
                        && countOccurrences(json, "{") == countOccurrences(json, "}")
                        && countOccurrences(json, "[") == countOccurrences(json, "]"));

        System.out.printf("%n通过 %d, 失败 %d%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ 词表读取辅助

    private static Map<String, List<String>> codesByScope() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String scope : StatusVocabulary.scopes()) {
            out.put(scope, codes(scope));
        }
        return out;
    }

    private static List<String> codes(String scope) {
        List<String> out = new ArrayList<>();
        for (StatusVocabulary.StatusDef d : StatusVocabulary.statuses(scope)) {
            out.add(d.code());
        }
        return out;
    }

    /** terminal=true 的 code（按词表顺序），按 scope 分组；false 时取 active。 */
    private static Map<String, List<String>> flagByScope(boolean terminal) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String scope : StatusVocabulary.scopes()) {
            List<String> hit = new ArrayList<>();
            for (StatusVocabulary.StatusDef d : StatusVocabulary.statuses(scope)) {
                if (terminal ? d.terminal() : d.active()) {
                    hit.add(d.code());
                }
            }
            out.put(scope, hit);
        }
        return out;
    }

    private static boolean labelsAndColorsOk() {
        for (String scope : StatusVocabulary.scopes()) {
            for (StatusVocabulary.StatusDef d : StatusVocabulary.statuses(scope)) {
                if (d.label() == null || d.label().isBlank()) {
                    System.out.println("      label 空白: " + scope + "/" + d.code());
                    return false;
                }
                if (!ALLOWED_COLORS.contains(d.color())) {
                    System.out.println("      非法 color: " + scope + "/" + d.code()
                            + " = " + d.color());
                    return false;
                }
            }
        }
        return true;
    }

    private static List<String> pauseKindFacts() {
        List<String> out = new ArrayList<>();
        for (StatusVocabulary.PauseKindDef k : StatusVocabulary.pauseKinds()) {
            boolean hintOk = k.hint() != null && !k.hint().isBlank();
            out.add(k.code() + "|" + k.label() + "|" + k.color() + "|" + k.auto()
                    + "|" + (hintOk ? "hint-ok" : "hint-empty"));
        }
        return out;
    }

    private static List<String> holdKindFacts() {
        List<String> out = new ArrayList<>();
        for (StatusVocabulary.HoldKindDef k : StatusVocabulary.holdKinds()) {
            out.add(k.code() + "|" + k.label() + "|" + k.color());
        }
        return out;
    }

    private static List<String> holdScopeFacts() {
        List<String> out = new ArrayList<>();
        for (StatusVocabulary.HoldScopeDef s : StatusVocabulary.holdScopes()) {
            out.add(s.code() + "|" + s.label());
        }
        return out;
    }

    // ------------------------------------------------------------ SQL CASE 求值

    /** 按 SQL CASE 语义求值生成的片段：首个 WHEN 命中取 THEN，否则原状态（ELSE r.status）。 */
    private static String evaluateGeneratedCase(String sql, String runStatus) {
        Matcher m = CASE_WHEN.matcher(sql);
        while (m.find()) {
            if (m.group(1).equals(runStatus)) {
                return m.group(2);
            }
        }
        return runStatus;
    }

    /** 改前 ExperimentExecutor.syncExperimentStatus 里的硬编码规则（行为基线）。 */
    private static String baselineCase(String runStatus) {
        return switch (runStatus) {
            case "completed" -> "finished";
            case "failed" -> "failed";
            case "running" -> "running";
            default -> runStatus;
        };
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

    private static int countOccurrences(String s, String needle) {
        int n = 0;
        int i = 0;
        while ((i = s.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    private static void section(String title) {
        System.out.println("\n── " + title + " ──");
    }

    private StatusVocabularySelfTest() {
    }
}
