package com.labos.kernel.workflow;

import com.labos.kernel.api.QueryService;
import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Set;

/**
 * N-10 显式 Workcell 绑定落库自测（需要真实库）。
 *
 * <p>验证：① 有显式绑定时以其为权威；② 清空绑定后回退 experiment.island_id；
 * ③ 绑定写入口全量替换 + 幂等。
 */
public final class ExperimentWorkcellScopeDbSelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;
    private static final long TENANT = 1L;
    private static final long EXP = -993001L;
    private static final long FALLBACK_ISLAND = 993300L;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();
        try {
            QueryService q = new QueryService(db, TENANT);
            seed();
            int n = q.setExperimentWorkcells(EXP, List.of(993101L, 993102L));
            check("绑定写入口写入 2 条", n == 2, n);
            Set<Long> explicit = ExperimentWorkcellScope.islandsOf(db, TENANT, EXP);
            check("显式绑定作为权威来源", explicit.equals(Set.of(993101L, 993102L)), explicit);
            check("显式绑定不再回退 experiment.island_id",
                    !explicit.contains(FALLBACK_ISLAND), explicit);

            int cleared = q.setExperimentWorkcells(EXP, List.of());
            check("空列表清空绑定", cleared == 0, cleared);
            Set<Long> fallback = ExperimentWorkcellScope.islandsOf(db, TENANT, EXP);
            check("无显式绑定时回退 experiment.island_id",
                    fallback.contains(FALLBACK_ISLAND), fallback);
            check("实验不存在时写入口返回 -1",
                    q.setExperimentWorkcells(-993999L, List.of(993101L)) == -1, -1);
        } finally {
            cleanup();
        }
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void seed() throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO experiment (id, tenant_id, experiment_no, name, status, island_id)
                     VALUES (?, ?, ?, ?, 'created', ?)
                     """)) {
            ps.setLong(1, EXP);
            ps.setLong(2, TENANT);
            ps.setString(3, "SELF-WC-P");
            ps.setString(4, "SELF-WC-P");
            ps.setLong(5, FALLBACK_ISLAND);
            ps.executeUpdate();
        }
    }

    private static void cleanup() {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM experiment WHERE tenant_id = ? AND id = ?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, EXP);
            ps.executeUpdate();
        } catch (Exception ignored) {
            // 清理失败不影响结论
        }
    }

    private static void check(String name, boolean ok, Object actual) {
        if (ok) {
            pass++;
            System.out.println("  ✓ " + name);
        } else {
            fail++;
            System.out.println("  ✗ " + name + "（实际：" + actual + "）");
        }
    }
}
