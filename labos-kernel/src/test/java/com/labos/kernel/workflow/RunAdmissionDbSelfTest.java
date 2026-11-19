package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 统一准入（{@link RunAdmission}）连库自测。
 *
 * <p>验证「准入与建 run 在同一事务」这条改造：
 *
 * <ol>
 *   <li>无活跃 → 准入成功，返回 runId，库里正好一条 running；</li>
 *   <li><b>同流程再准入 → Rejected，且库里 run 总数不变</b>（证明事务回滚，没留下半条）；</li>
 *   <li>另一个流程不受影响；</li>
 *   <li>把第一条置 completed → 可以再启动，且是新 run。</li>
 * </ol>
 *
 * <p>第 2 条是本测试的核心：只断言「被拒」不够，还要断言「被拒后库里没有多出 run」——
 * 否则 {@code Rejected} 抛了但事务没回滚（比如误用受检异常），单看返回值发现不了。
 *
 * <p>数据隔离：workflow 用 99973xx 段、run 按 workflow_id 清理。
 * 不指定 run.id（由 identity 生成），避免与真实数据撞号。
 */
public final class RunAdmissionDbSelfTest {

    private static final long TENANT = 1L;
    private static final long WF_A = 9997301L;
    private static final long WF_B = 9997302L;
    /** 只建 run，不连驱动。 */
    private static final int DRIVER_PORT = 0;

    private static DbConfig db;
    private static WorkflowService svc;
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        svc = new WorkflowService(db, TENANT, DRIVER_PORT);
        cleanup();

        try {
            System.out.println("════ 统一准入 RunAdmission · 连库自测 ════");

            section("0. 前置：并发开关必须是关的（否则后面的拒绝断言没意义）");
            eq("LABOS_WORKFLOW_CONCURRENT_RUN 未放开", false, WorkflowRunGate.concurrentAllowed());

            setup();

            section("1. 无活跃 → 准入成功，且只建一条 run");
            long run1 = RunAdmission.start(db, TENANT, WF_A, "attended", svc);
            eq("返回 runId > 0", true, run1 > 0);
            eq("WF_A 的活跃 run 数", 1, countActive(WF_A));
            eq("该 run 状态", "running", statusOf(run1));

            section("2. 同流程再准入 → 被拒，且库里 run 总数不变（事务回滚）");
            int before = countAll(WF_A);
            boolean rejected = false;
            try {
                RunAdmission.start(db, TENANT, WF_A, "attended", svc);
            } catch (RunAdmission.Rejected rej) {
                rejected = true;
                eq("拒绝指向正在跑的那条", run1, rej.decision().runId());
                eq("拒绝原因非空", true,
                        rej.decision().reason() != null && !rej.decision().reason().isBlank());
            }
            eq("确实抛出 Rejected", true, rejected);
            eq("run 总数没变", before, countAll(WF_A));

            section("3. 另一个流程不受影响");
            long runB = RunAdmission.start(db, TENANT, WF_B, "attended", svc);
            eq("WF_B 也能启动", true, runB > 0);
            eq("WF_A 仍只有一条活跃", 1, countActive(WF_A));
            eq("WF_B 有一条活跃", 1, countActive(WF_B));

            section("4. 结束 WF_A 的 run → 可以再启动");
            finish(run1);
            long run2 = RunAdmission.start(db, TENANT, WF_A, "attended", svc);
            eq("再次启动成功", true, run2 > 0);
            eq("是新的 run", true, run2 != run1);
            eq("WF_A 活跃数回到 1", 1, countActive(WF_A));

            section("5. 唯一索引兜底：绕过准入直接插重复 run 必须失败");
            boolean dupBlocked = false;
            try (Connection c = db.open();
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO run (tenant_id, run_no, run_type, workflow_id, status) "
                                 + "VALUES (?, ?, 'workflow', ?, 'running')")) {
                ps.setLong(1, TENANT);
                ps.setString(2, "SELFTEST-DUP-" + System.nanoTime());
                ps.setLong(3, WF_A);
                ps.executeUpdate();
            } catch (SQLException e) {
                dupBlocked = e.getMessage() != null
                        && e.getMessage().contains("uk_run_active_workflow");
            }
            eq("直接插重复被唯一索引拦下", true, dupBlocked);
        } finally {
            cleanup();
        }

        System.out.println("\n通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 数据准备

    private static void setup() throws SQLException {
        exec("INSERT INTO workflow (id, tenant_id, code, name) VALUES (" + WF_A + ", " + TENANT
                + ", 'SELFTEST-RA-A', '准入自测A') ON CONFLICT DO NOTHING");
        exec("INSERT INTO workflow (id, tenant_id, code, name) VALUES (" + WF_B + ", " + TENANT
                + ", 'SELFTEST-RA-B', '准入自测B') ON CONFLICT DO NOTHING");
    }

    private static void cleanup() {
        try {
            exec("DELETE FROM run WHERE workflow_id IN (" + WF_A + ", " + WF_B + ")");
            exec("DELETE FROM workflow WHERE id IN (" + WF_A + ", " + WF_B + ")");
        } catch (SQLException e) {
            System.out.println("  ! cleanup 失败（忽略）：" + e.getMessage());
        }
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- 查询断言

    private static int countActive(long workflowId) throws SQLException {
        return count("SELECT count(*) FROM run WHERE workflow_id = " + workflowId
                + " AND deleted_at IS NULL"
                + " AND status::text IN ('queued','running','paused','quarantined')");
    }

    private static int countAll(long workflowId) throws SQLException {
        return count("SELECT count(*) FROM run WHERE workflow_id = " + workflowId);
    }

    private static int count(String sql) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private static String statusOf(long runId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status::text FROM run WHERE id = ?")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void finish(long runId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE run SET status = 'completed', actual_end = now() WHERE id = ?")) {
            ps.setLong(1, runId);
            ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- 断言输出

    private static void section(String title) {
        System.out.println("\n── " + title + " ──");
    }

    private static void eq(String what, Object expect, Object actual) {
        boolean ok = expect == null ? actual == null : expect.equals(actual);
        if (ok) {
            pass++;
            System.out.println("  ✔ " + what + "（" + actual + "）");
        } else {
            fail++;
            System.out.println("  ✘ " + what + "：期望 " + expect + "，实际 " + actual);
        }
    }
}
