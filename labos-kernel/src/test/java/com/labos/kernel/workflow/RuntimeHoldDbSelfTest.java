package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 作用域扣留 落库自测（需要真实库）。
 *
 * <p>重点验证三条"不能商量"的规则：
 * <ol>
 *   <li><b>同一 (范围, 范围 id) 只能有一条生效</b> —— 否则同一现场事故会被重复记 N 条，
 *       解除时解掉一条、剩下 N-1 条仍在扣留，界面显示"已解除但现场没恢复"；</li>
 *   <li><b>重复 raise 幂等</b>：第二次返回 false 且不新增行；</li>
 *   <li><b>解除后不再覆盖</b>：clear 之后再覆盖查询必须查不到。</li>
 * </ol>
 *
 * <p>用合成 id（负数、不可能与真实实验冲突），并在 finally 里清干净。
 * <b>不用 site 范围</b>——那是一道全场闸门，跑自测时会把正在跑的实验一起停掉。
 */
public final class RuntimeHoldDbSelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;
    private static final long TENANT = 1L;

    /** 合成 id：负数，与真实自增 id（正数）不可能撞。 */
    private static final long EXP_ID = -990001L;
    private static final long EXP_OTHER = -990002L;
    private static final long ISLAND_ID = -990003L;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();
        try {
            RuntimeHold h = new RuntimeHold(db, TENANT);

            // ---- 1. 多 Hold 并存（同 scope 每个 kind 各一条；同 kind 幂等）----
            boolean first = h.raise(RuntimeHold.Scope.EXPERIMENT, EXP_ID,
                    RuntimeHold.Kind.MANUAL, "自测：人工暂停", null);
            check("首次扣留生效", first, first);
            boolean secondKind = h.raise(RuntimeHold.Scope.EXPERIMENT, EXP_ID,
                    RuntimeHold.Kind.FAULT, "自测：设备故障", null);
            check("不同 kind 可并存（P-03 多 Hold）", secondKind, secondKind);
            boolean sameKind = h.raise(RuntimeHold.Scope.EXPERIMENT, EXP_ID,
                    RuntimeHold.Kind.MANUAL, "自测：重复抬闸", null);
            check("同 kind 重复 raise 幂等（返回 false）", !sameKind, sameKind);
            check("同 scope 两条生效（manual + fault）", countActive(EXP_ID) == 2,
                    countActive(EXP_ID));

            // ---- 2. 覆盖查询 ----
            RuntimeHold.Active cov = h.coveringFor(EXP_ID, 0L);
            check("covering 命中实验级扣留", cov != null
                    && RuntimeHold.Scope.EXPERIMENT.code().equals(cov.scope()), cov);

            RuntimeHold.Active notMine = h.coveringFor(EXP_OTHER, 0L);
            check("covering 不命中无关实验", !isMine(notMine, EXP_OTHER), notMine);

            // ---- 3. 解除 ----
            boolean clearedKind = h.clearKind(RuntimeHold.Scope.EXPERIMENT, EXP_ID,
                    RuntimeHold.Kind.MANUAL, null, "自测：只解人工暂停");
            check("按 kind 解除成功（只解 manual）", clearedKind, clearedKind);
            check("另一类 Hold 仍在（fault）", countActive(EXP_ID) == 1, countActive(EXP_ID));
            boolean clearedKindAgain = h.clearKind(RuntimeHold.Scope.EXPERIMENT, EXP_ID,
                    RuntimeHold.Kind.MANUAL, null, "自测：再解");
            check("同 kind 重复解除返回 false", !clearedKindAgain, clearedKindAgain);

            boolean cleared = h.clear(RuntimeHold.Scope.EXPERIMENT, EXP_ID, null, "自测：解除全部");
            check("解除全部成功", cleared, cleared);
            boolean clearedAgain = h.clear(RuntimeHold.Scope.EXPERIMENT, EXP_ID, null, "自测：再解");
            check("重复解除返回 false", !clearedAgain, clearedAgain);
            check("解除后覆盖查询为空", !isMine(h.coveringFor(EXP_ID, 0L), EXP_ID),
                    h.coveringFor(EXP_ID, 0L));

            // ---- 4. 岛级扣留只停自己的岛 ----
            RuntimeHold isl = new RuntimeHold(db, TENANT);
            isl.raise(RuntimeHold.Scope.ISLAND, ISLAND_ID, RuntimeHold.Kind.FAULT,
                    "自测：岛级故障", null);
            RuntimeHold.Active onIsland = isl.coveringFor(EXP_ID, ISLAND_ID);
            check("岛级扣留命中同岛实验", onIsland != null
                    && RuntimeHold.Scope.ISLAND.code().equals(onIsland.scope()), onIsland);
            check("岛级扣留不命中别岛",
                    isl.coveringFor(EXP_ID, ISLAND_ID + 1) == null,
                    isl.coveringFor(EXP_ID, ISLAND_ID + 1));
            isl.clear(RuntimeHold.Scope.ISLAND, ISLAND_ID, null, "自测：解除岛级");
        } finally {
            cleanup();
        }

        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** 命中的扣留是不是"我这条实验"的实验级扣留。 */
    private static boolean isMine(RuntimeHold.Active a, long expId) {
        return a != null
                && RuntimeHold.Scope.EXPERIMENT.code().equals(a.scope())
                && a.scopeId() != null && a.scopeId() == expId;
    }

    private static long countActive(long scopeId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT count(*) FROM runtime_hold
                      WHERE tenant_id = ? AND scope = 'experiment' AND scope_id = ?
                        AND released_at IS NULL
                     """)) {
            ps.setLong(1, TENANT);
            ps.setLong(2, scopeId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** 删掉自测造的行（物理删除：自测数据不该留在现场闸门表里）。 */
    private static void cleanup() throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     DELETE FROM runtime_hold
                      WHERE tenant_id = ? AND scope_id IN (?, ?, ?)
                     """)) {
            ps.setLong(1, TENANT);
            ps.setLong(2, EXP_ID);
            ps.setLong(3, EXP_OTHER);
            ps.setLong(4, ISLAND_ID);
            ps.executeUpdate();
        }
    }

    private static void check(String name, boolean ok, Object actual) {
        if (ok) {
            pass++;
            System.out.printf("  ✓ %-30s %s%n", name, actual);
        } else {
            fail++;
            System.out.printf("  ✗ %-30s 实际 %s%n", name, actual);
        }
    }

    private RuntimeHoldDbSelfTest() {
    }
}
