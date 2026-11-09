package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 状态词表 ↔ 数据库落库能力的一致性核实（P-11，需要真实库）。
 *
 * <p>为什么需要这条测试：{@code StatusVocabulary} 是「状态码 → 文案/颜色」的唯一权威，
 * 但它描述的是**能给界面看什么**，不保证**库里存得下**。两者不一致时不会立刻报错，
 * 而是等到某条路径真去写那个码才炸（{@code invalid input value for enum ...}），
 * 或者界面显示一个永远不会出现的状态。
 *
 * <p>P-11 核实出的两处真实不一致（2026-11-09）：
 * <ul>
 *   <li>{@code run} scope 有 {@code cancelled}，而 PG 枚举 {@code run_status} 没有
 *       ⇒ 补 DDL（{@code 99t_run_status_cancelled.sql}）；</li>
 *   <li>{@code step} scope 有 {@code paused}（没有任何写入路径）且**缺** {@code aborted}
 *       （{@code WorkflowService.stepStatus()} 会写 aborted）⇒ 改词表。</li>
 * </ul>
 *
 * <p>本测试把这两件事变成常驻断言：枚举类 scope **双向相等**，varchar 类 scope
 * 保证「最长码放得进列」。
 */
public final class StatusVocabularyDbSelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;

    /** 枚举类 scope：词表码集合必须与 PG 枚举成员完全一致（双向）。 */
    private static final Map<String, String> ENUM_SCOPES = new LinkedHashMap<>();

    /** varchar 类 scope：scope → 表.列。 */
    private static final Map<String, String> VARCHAR_SCOPES = new LinkedHashMap<>();

    static {
        ENUM_SCOPES.put(StatusVocabulary.SCOPE_RUN, "run_status");
        ENUM_SCOPES.put(StatusVocabulary.SCOPE_NODE, "node_status");
        ENUM_SCOPES.put(StatusVocabulary.SCOPE_STEP, "node_status");
        VARCHAR_SCOPES.put(StatusVocabulary.SCOPE_EXPERIMENT, "experiment.status");
        VARCHAR_SCOPES.put(StatusVocabulary.SCOPE_BATCH, "experiment_batch.status");
        VARCHAR_SCOPES.put(StatusVocabulary.SCOPE_CONTROL, "experiment_run.control_state");
    }

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        try {
            checkEnumScopes();
            checkVarcharScopes();
        } finally {
            System.out.printf("%n通过 %d, 失败 %d%n", pass, fail);
        }
        if (fail > 0) {
            System.exit(1);
        }
    }

    /**
     * 枚举类：
     * <ol>
     *   <li>每个 scope 的码必须都在枚举里（否则写到那个码就报 invalid input value）；</li>
     *   <li>枚举的每个成员必须至少被某一个 scope 覆盖（否则界面只能显示原始码）。
     *       反向检查要按**枚举**聚合，不能按 scope —— 同一个 {@code node_status}
     *       被 node / step 两个 scope 共享，step 是 node 的子集是设计如此
     *       （ready/manual/bypassed 这些是节点层的状态，step 不会出现）。</li>
     * </ol>
     */
    private static void checkEnumScopes() throws SQLException {
        Map<String, Set<String>> vocabByEnum = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : ENUM_SCOPES.entrySet()) {
            String scope = e.getKey();
            String type = e.getValue();
            Set<String> dbCodes = new LinkedHashSet<>(enumLabels(type));
            Set<String> vocab = new LinkedHashSet<>(codesOf(scope));
            vocabByEnum.computeIfAbsent(type, k -> new LinkedHashSet<>()).addAll(vocab);

            List<String> onlyVocab = new ArrayList<>(vocab);
            onlyVocab.removeAll(dbCodes);
            check(scope + " → " + type + "：词表码全部可写入（" + vocab.size() + " 个）",
                    onlyVocab.isEmpty(), onlyVocab);
        }
        for (Map.Entry<String, Set<String>> e : vocabByEnum.entrySet()) {
            List<String> onlyDb = new ArrayList<>(new LinkedHashSet<>(enumLabels(e.getKey())));
            onlyDb.removeAll(e.getValue());
            check(e.getKey() + "：枚举成员都有文案（" + e.getValue().size() + " 个码覆盖）",
                    onlyDb.isEmpty(), onlyDb);
        }
    }

    /** varchar 类：只要求放得下（取值没有枚举约束）。 */
    private static void checkVarcharScopes() throws SQLException {
        for (Map.Entry<String, String> e : VARCHAR_SCOPES.entrySet()) {
            String scope = e.getKey();
            String[] tc = e.getValue().split("\\.");
            int maxLen = charLength(tc[0], tc[1]);
            String longest = "";
            for (String c : codesOf(scope)) {
                if (c.length() > longest.length()) {
                    longest = c;
                }
            }
            check(scope + " → " + e.getValue() + "：最长码 '" + longest + "'(" + longest.length()
                            + ") ≤ 列宽 " + maxLen,
                    maxLen > 0 && longest.length() <= maxLen, maxLen);
        }
    }

    private static List<String> codesOf(String scope) {
        List<String> codes = new ArrayList<>();
        for (StatusVocabulary.StatusDef d : StatusVocabulary.statuses(scope)) {
            codes.add(d.code());
        }
        return codes;
    }

    private static List<String> enumLabels(String type) throws SQLException {
        List<String> labels = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT e.enumlabel
                       FROM pg_type t JOIN pg_enum e ON e.enumtypid = t.oid
                      WHERE t.typname = ?
                      ORDER BY e.enumsortorder
                     """)) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    labels.add(rs.getString(1));
                }
            }
        }
        return labels;
    }

    private static int charLength(String table, String column) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT COALESCE(character_maximum_length, 0)
                       FROM information_schema.columns
                      WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                     """)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static void check(String name, boolean ok, Object actual) {
        if (ok) {
            pass++;
            System.out.println("  ✓ " + name + " " + actual);
        } else {
            fail++;
            System.out.println("  ✗ " + name + " " + actual);
        }
    }

    private StatusVocabularyDbSelfTest() {
    }
}
