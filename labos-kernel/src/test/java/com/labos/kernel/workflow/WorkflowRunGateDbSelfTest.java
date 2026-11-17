package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;

/**
 * 同一流程并发运行门禁（{@link WorkflowRunGate}）连库判别性自测。
 *
 * <p>验证「在途 run 拦得住、不影响别的流程、能把被挡的那个排除掉、终态就不拦」：
 * <ul>
 *   <li>无在途 → 允许；</li>
 *   <li>同流程 running → 拒绝，并指出是哪一单；</li>
 *   <li>同流程 paused → 也拒绝（paused 仍是 active，随时会续跑）；</li>
 *   <li>同流程 draft/pending → 不拒绝（没在跑）；</li>
 *   <li>{@code excludeRunId} 指向被挡的那单 → 允许（续跑场景）；</li>
 *   <li>把在途改成 completed → 允许；</li>
 *   <li>**另一个流程**在跑 → 不影响本流程。</li>
 * </ul>
 *
 * <p>⚠ 依赖 {@code LABOS_WORKFLOW_CONCURRENT_RUN} 未设置（默认禁止并发）；
 * 若外面设成 allow，本用例会全绿但没意义 —— 所以开头显式断言开关是关的。
 *
 * <p>数据隔离：workflow id 用 99971xx、run id 用 99972xx 段，cleanup 按 id 精确删。
 */
public final class WorkflowRunGateDbSelfTest {

    private static final long TENANT = 1L;
    private static final long WF_A = 9997101L;
    private static final long WF_B = 9997102L;
    private static final long RUN_RUNNING = 9997201L;
    private static final long RUN_PAUSED = 9997202L;
    private static final long RUN_DRAFT = 9997203L;
    private static final long RUN_OTHER_WF = 9997204L;

    private static DbConfig db;
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();

        try {
            System.out.println("════ 同一流程并发运行门禁 · 连库自测 ════");
            section("0. 前置：开关必须是关的（否则后面的断言没意义）");
            eq("LABOS_WORKFLOW_CONCURRENT_RUN 未放开", false, WorkflowRunGate.concurrentAllowed());
            eq("在途状态词表含 running/paused", true,
                    WorkflowRunGate.activeStatuses().contains("running")
                            && WorkflowRunGate.activeStatuses().contains("paused"));
            eq("在途状态词表不含 draft/pending", false,
                    WorkflowRunGate.activeStatuses().contains("draft")
                            || WorkflowRunGate.activeStatuses().contains("pending"));

            seed();

            section("1. 无在途 run → 允许");
            eq("允许", true, WorkflowRunGate.check(db, TENANT, WF_A, null).allowed());

            section("2. 同流程有 running → 拒绝，并指出是哪一单");
            setStatus(RUN_RUNNING, "running");
            WorkflowRunGate.Decision d2 = WorkflowRunGate.check(db, TENANT, WF_A, null);
            eq("拒绝", false, d2.allowed());
            eq("指向 RUN_RUNNING", RUN_RUNNING, d2.runId());
            eq("状态 running", "running", d2.status());
            eq("理由里带 runNo", true,
                    d2.reason() != null && d2.reason().contains("SELF-GATE-A1"));

            section("3. 排除它自己 → 允许（续跑同一 run 的场景）");
            eq("允许", true, WorkflowRunGate.check(db, TENANT, WF_A, RUN_RUNNING).allowed());

            section("4. paused 也是在途 → 拒绝");
            setStatus(RUN_RUNNING, "completed");
            setStatus(RUN_PAUSED, "paused");
            eq("拒绝", false, WorkflowRunGate.check(db, TENANT, WF_A, null).allowed());

            section("5. draft / pending 没有在跑 → 不拒绝");
            setStatus(RUN_PAUSED, "completed");
            setStatus(RUN_DRAFT, "draft");
            eq("允许", true, WorkflowRunGate.check(db, TENANT, WF_A, null).allowed());

            section("6. 另一个流程在跑 → 不影响本流程");
            setStatus(RUN_OTHER_WF, "running");
            eq("本流程允许", true, WorkflowRunGate.check(db, TENANT, WF_A, null).allowed());
            eq("另一流程拒绝", false, WorkflowRunGate.check(db, TENANT, WF_B, null).allowed());
        } finally {
            cleanup();
        }

        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void seed() throws SQLException {
        exec("INSERT INTO workflow (id, tenant_id, code, name) VALUES (?, ?, 'SELF-GATE-A', '门禁自测A')",
                WF_A, TENANT);
        exec("INSERT INTO workflow (id, tenant_id, code, name) VALUES (?, ?, 'SELF-GATE-B', '门禁自测B')",
                WF_B, TENANT);
        for (long[] r : new long[][]{
                {RUN_RUNNING, WF_A}, {RUN_PAUSED, WF_A}, {RUN_DRAFT, WF_A}, {RUN_OTHER_WF, WF_B}}) {
            exec("INSERT INTO run (id, tenant_id, run_no, run_type, workflow_id, status) "
                            + "VALUES (?, ?, ?, 'workflow', ?, 'pending')",
                    r[0], TENANT, "SELF-GATE-" + (r[0] == RUN_RUNNING ? "A1"
                            : r[0] == RUN_PAUSED ? "A2"
                            : r[0] == RUN_DRAFT ? "A3" : "B1"), r[1]);
        }
    }

    private static void setStatus(long runId, String status) throws SQLException {
        // run.status 是枚举类型 run_status ⇒ 字符串参数必须显式 ::run_status 转换
        exec("UPDATE run SET status = ?::run_status WHERE tenant_id = ? AND id = ?",
                status, TENANT, runId);
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

    private static void cleanup() {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM run WHERE tenant_id = ? AND id IN (?, ?, ?, ?)")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, RUN_RUNNING);
            ps.setLong(3, RUN_PAUSED);
            ps.setLong(4, RUN_DRAFT);
            ps.setLong(5, RUN_OTHER_WF);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("  [清理] run 失败：" + e.getMessage());
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM workflow WHERE tenant_id = ? AND id IN (?, ?)")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, WF_A);
            ps.setLong(3, WF_B);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("  [清理] workflow 失败：" + e.getMessage());
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("── " + title + " ──");
    }

    private static void eq(String name, Object expect, Object got) {
        if (Objects.equals(expect, got)) {
            pass++;
            System.out.println("  ✔ " + name + (got == null ? "" : "（" + got + "）"));
        } else {
            fail++;
            System.out.println("  ✘ " + name + " 期望 " + expect + "，实际 " + got);
        }
    }
}
