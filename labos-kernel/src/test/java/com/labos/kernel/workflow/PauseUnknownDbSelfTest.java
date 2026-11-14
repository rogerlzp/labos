package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;
import com.labos.kernel.persistence.ExecutionMapper;
import com.labos.kernel.persistence.Mappers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * R3 · 暂停确认状态机（PauseUnknown）的连库自测。
 *
 * <p>验证两个事实：
 * <ol>
 *   <li>{@code ExecutionMapper.countReconciliationRequired} 能数到「物理结果不确定」的节点；</li>
 *   <li>{@code ExperimentExecutor.settle(..., "paused", ...)} 在存在该节点时把
 *       {@code pause_kind} 从 waiting_human 改成 {@code manual}（PauseUnknown 转人工对账），
 *       而不是写干净的 paused。</li>
 * </ol>
 */
public final class PauseUnknownDbSelfTest {

    private static final long TENANT = 1L;
    private static final long EXP = -995000L;
    private static final long WF_RUN = -995001L;
    private static final long NODE = -995002L;
    private static final long EXP_RUN = -995003L;
    // 反例（无 reconciliation_required，应保持干净 paused）
    private static final long EXP2 = -995004L;
    private static final long WF_RUN2 = -995005L;
    private static final long NODE2 = -995006L;
    private static final long EXP_RUN2 = -995007L;

    private static DbConfig db;
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();
        try {
            seed();

            ExecutionMapper em = Mappers.get(ExecutionMapper.class);
            section("1. countReconciliationRequired 命中");
            int n = em.countReconciliationRequired(TENANT, EXP_RUN);
            if (n == 1) {
                ok("reconciliation_required 节点计数 = 1", String.valueOf(n));
            } else {
                bad("计数不符", "n=" + n);
            }

            section("2. 暂停收敛 → PauseUnknown（pause_kind 转 manual）");
            ExperimentExecutor executor = new ExperimentExecutor(db, TENANT,
                    new WorkflowService(db, TENANT, 18080));
            executor.settle(EXP_RUN, "paused", 0, null, "waiting_human", "验证暂停", true);
            String pk = queryOne("SELECT pause_kind FROM experiment_run WHERE tenant_id = ? AND id = ?",
                    TENANT, EXP_RUN);
            if ("manual".equals(pk)) {
                ok("pause_kind → manual（PauseUnknown 转人工）", pk);
            } else {
                bad("pause_kind 未转 manual", String.valueOf(pk));
            }

            section("3. 反例：无 reconciliation_required 保持干净 paused");
            executor.settle(EXP_RUN2, "paused", 0, null, "waiting_human", "验证暂停", true);
            String pk2 = queryOne("SELECT pause_kind FROM experiment_run WHERE tenant_id = ? AND id = ?",
                    TENANT, EXP_RUN2);
            if ("waiting_human".equals(pk2)) {
                ok("无不确定节点 → pause_kind 保持 waiting_human（不是一律转 manual）", pk2);
            } else {
                bad("无不确定节点也被转 manual（假阳性）", String.valueOf(pk2));
            }
        } finally {
            cleanup();
        }

        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ================================================================ 造数 / 清理

    private static void seed() throws SQLException {
        // workflow run
        exec("INSERT INTO run (id, tenant_id, run_no, run_type) VALUES (?, ?, 'VERIFY-PAUSEUNK-WF', 'workflow')",
                WF_RUN, TENANT);
        // 一个「物理结果不确定」的节点
        exec("INSERT INTO run_node (id, tenant_id, run_id, node_key, status, level, "
                + "execution_resolution_status) "
                + "VALUES (?, ?, ?, 'T1', 'running', 0, 'reconciliation_required')",
                NODE, TENANT, WF_RUN);
        // 实验 + experiment_run（experiment.run_id → workflow run）
        exec("INSERT INTO experiment (id, tenant_id, experiment_no, name, run_id) "
                + "VALUES (?, ?, 'VERIFY-PAUSEUNK-EXP', '暂停确认验证', ?)", EXP, TENANT, WF_RUN);
        exec("INSERT INTO experiment_run (id, tenant_id, experiment_id, status) "
                + "VALUES (?, ?, ?, 'running')", EXP_RUN, TENANT, EXP);

        // 反例：无 reconciliation_required（节点 resolution=unresolved，非不确定态）
        exec("INSERT INTO run (id, tenant_id, run_no, run_type) VALUES (?, ?, 'VERIFY-PAUSEUNK-WF2', 'workflow')",
                WF_RUN2, TENANT);
        exec("INSERT INTO run_node (id, tenant_id, run_id, node_key, status, level, "
                + "execution_resolution_status) "
                + "VALUES (?, ?, ?, 'T1', 'running', 0, 'unresolved')",
                NODE2, TENANT, WF_RUN2);
        exec("INSERT INTO experiment (id, tenant_id, experiment_no, name, run_id) "
                + "VALUES (?, ?, 'VERIFY-PAUSEUNK-EXP2', '暂停确认反例', ?)", EXP2, TENANT, WF_RUN2);
        exec("INSERT INTO experiment_run (id, tenant_id, experiment_id, status) "
                + "VALUES (?, ?, ?, 'running')", EXP_RUN2, TENANT, EXP2);
    }

    private static void cleanup() {
        // 先删 experiment_run（FK→experiment），再删 run_node（FK→run），再删 experiment/run
        del("experiment_run", "DELETE FROM experiment_run WHERE id IN (?, ?)", EXP_RUN, EXP_RUN2);
        del("run_node", "DELETE FROM run_node WHERE id IN (?, ?)", NODE, NODE2);
        del("experiment", "DELETE FROM experiment WHERE id IN (?, ?)", EXP, EXP2);
        del("run", "DELETE FROM run WHERE id IN (?, ?)", WF_RUN, WF_RUN2);
    }

    // ================================================================ 小工具

    private static String queryOne(String sql, Object... args) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void exec(String sql, Object... args) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private static void del(String label, String sql, Object... args) {
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    for (int i = 0; i < args.length; i++) {
                        ps.setObject(i + 1, args[i]);
                    }
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                System.out.println("  [清理] " + label + " 失败：" + e.getMessage().replace('\n', ' '));
            }
        } catch (SQLException e) {
            System.out.println("  [清理] " + label + " 连接失败：" + e.getMessage());
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("── " + title + " ──");
    }

    private static void ok(String msg, String detail) {
        pass++;
        System.out.println("  ✓ " + msg + (detail == null || detail.isEmpty() ? "" : "（" + detail + "）"));
    }

    private static void bad(String msg, String detail) {
        fail++;
        System.out.println("  ✗ " + msg + (detail == null || detail.isEmpty() ? "" : "（" + detail + "）"));
    }

    private PauseUnknownDbSelfTest() {
    }
}
