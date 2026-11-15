package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * R5 · 重启对账阻断态的连库自测。
 *
 * <p>验证三条：
 * <ol>
 *   <li>{@code rehydrateQueued} 把 queued run 装进内存队列 —— 否则后面「派发 0 条」
 *       可能只是因为队列空，断言会假绿；</li>
 *   <li>{@code reconciling} 期间 {@code dispatch} 返回 0，且**确实走了阻断分支**
 *       （{@code reconcilingSkips} 增长），queued run 保持 queued；</li>
 *   <li>{@code endReconciling} 后标志归位。</li>
 * </ol>
 *
 * <p><b>为什么解除阻断后不调 dispatchOnce</b>：解除后若真的派发，会起线程执行实验
 * （副作用不可控）。「解除后能派发」由 {@code dispatch()} 的早退点顺序保证
 * （阻断检查在第一个早退点），这里不重复验证。
 */
public final class ReconcilingGateDbSelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;
    private static final long TENANT = 1L;
    private static final long EXP = -998000L;
    private static final long RUN = -998001L;

    public static void main(String[] args) throws Exception {
        com.labos.kernel.support.TestSpringSupport.db();
        db = DbConfig.fromEnv();
        cleanup();
        try {
            seed();
            ExperimentScheduler scheduler = new ExperimentScheduler(db, TENANT,
                    new WorkflowService(db, TENANT, 18080));

            section("1. rehydrate 把 queued run 装进内存队列");
            int added = scheduler.rehydrateQueued();
            yes("rehydrate 至少装入 1 条", added >= 1);
            yes("内存队列非空（排除后续 0 派发的假绿）", scheduler.queueSize() >= 1);

            section("2. reconciling 期间不派发，且确实走阻断分支");
            long skips0 = scheduler.reconcilingSkips();
            scheduler.beginReconciling();
            yes("beginReconciling 后标志为 true", scheduler.isReconciling());

            int n = scheduler.dispatchOnce();
            eq("dispatch 返回 0 条", 0, n);
            yes("reconcilingSkips 增长（证明走的是阻断分支，不是队列空）",
                    scheduler.reconcilingSkips() > skips0);
            eq("queued run 仍是 queued（未被派发/未改状态）", "queued", statusOf(RUN));

            section("3. 解除阻断后标志归位");
            scheduler.endReconciling();
            yes("endReconciling 后标志为 false", !scheduler.isReconciling());
            // 幂等：重复解除不报错、标志仍 false
            scheduler.endReconciling();
            yes("重复 endReconciling 幂等", !scheduler.isReconciling());
        } finally {
            cleanup();
        }

        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ================================================================ 造数 / 清理

    private static void seed() throws Exception {
        exec("INSERT INTO experiment (id, tenant_id, experiment_no, name) "
                + "VALUES (" + EXP + ", " + TENANT + ", 'SELF-R5-EXP', 'R5 阻断态自测')");
        exec("INSERT INTO experiment_run (id, tenant_id, experiment_id, status) "
                + "VALUES (" + RUN + ", " + TENANT + ", " + EXP + ", 'queued')");
    }

    private static String statusOf(long runId) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status FROM experiment_run WHERE id = ?")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void cleanup() {
        try {
            exec("DELETE FROM experiment_run WHERE id = " + RUN);
            exec("DELETE FROM experiment WHERE id = " + EXP);
        } catch (Exception ignored) {
            // 清理失败不影响结论
        }
    }

    private static void exec(String sql) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    // ================================================================ 输出

    private static void section(String title) {
        System.out.println("── " + title + " ──");
    }

    private static void yes(String name, boolean ok) {
        check(name, ok, ok);
    }

    private static void eq(String name, int expect, int actual) {
        check(name, expect == actual, actual);
    }

    private static void eq(String name, String expect, String actual) {
        check(name, expect == null ? actual == null : expect.equals(actual), actual);
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

    private ReconcilingGateDbSelfTest() {
    }
}
