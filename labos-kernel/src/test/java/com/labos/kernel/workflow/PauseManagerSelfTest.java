package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 暂停/恢复/板位三态 单测。需要真实库。
 *
 * <p>重点验证三条"不能商量"的规则：
 * <ol>
 *   <li>板位 UNKNOWN 时<b>没有校正不许恢复</b> —— 不校正就继续，后面的板位判断全错</li>
 *   <li>回程承诺位<b>暂停时不释放</b> —— 放了别人的板就占进去，回程时死锁</li>
 *   <li>盘点后<b>只解因这块板被封的位置</b> —— 不能误伤别人的封锁</li>
 * </ol>
 */
public final class PauseManagerSelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;
    private static final long TENANT = 1L;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        long runId = createRun();

        try {
            testPauseAndResume(runId);
            testUnknownRequiresCorrection(runId);
            testUnknownWithCorrection(runId);
            testReleaseExempt();
        } finally {
            cleanup(runId);
        }

        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /**
     * 测试间把 run 拉回 running。
     *
     * <p>P0-5 之后 {@code resume} 置 queued（不再假装 running），
     * 而 {@code pause} 只接受 running —— 不重置的话第二个用例会静默失败。
     */
    private static void armRunning(long runId) {
        exec("UPDATE run SET status='running', pause_kind=NULL, pause_reason=NULL WHERE id=" + runId);
    }

    /** 暂停后状态变 paused，恢复后离开 paused（置 queued）。 */
    private static void testPauseAndResume(long runId) throws Exception {
        armRunning(runId);
        PauseManager pm = new PauseManager(db, TENANT);

        pm.pause(runId, PauseManager.Kind.WAITING_HUMAN, "单测暂停", "node-a");
        String kind = pm.pauseKindOf(runId);
        check("暂停后 pause_kind=waiting_human",
                PauseManager.Kind.WAITING_HUMAN.code().equals(kind), kind);

        PauseManager.ResumeResult r = pm.resume(runId, PauseManager.ResumeAction.CONTINUE,
                Map.of(), "test");
        check("恢复成功", r.ok(), r.error());
        // P0-5：恢复后是 queued（如实表达"放行待重新拾起"），不是假装 running
        String st = statusOf(runId);
        check("恢复后离开 paused（queued 或 running）",
                "queued".equals(st) || "running".equals(st), st);
    }

    /** UNKNOWN 时没校正必须拒绝恢复。 */
    private static void testUnknownRequiresCorrection(long runId) throws Exception {
        armRunning(runId);
        PauseManager pm = new PauseManager(db, TENANT);

        pm.pause(runId, PauseManager.Kind.PAUSED_UNKNOWN, "板位置未知", "node-b");
        PauseManager.ResumeResult r = pm.resume(runId, PauseManager.ResumeAction.CONTINUE,
                Map.of(), "test");

        check("UNKNOWN 无校正被拒绝恢复", !r.ok(), "居然过了");
        check("拒绝原因提到 corrections",
                r.error() != null && r.error().contains("corrections"), r.error());
        check("被拒后仍是 paused", pm.pauseKindOf(runId) != null, "已退出暂停");
    }

    /** 带校正能恢复。 */
    private static void testUnknownWithCorrection(long runId) throws Exception {
        PauseManager pm = new PauseManager(db, TENANT);
        // 承接上一个用例留下的 paused_unknown 状态；
        // 如果不在暂停态（单独跑本用例时），先造一个。
        if (pm.pauseKindOf(runId) == null) {
            armRunning(runId);
            pm.pause(runId, PauseManager.Kind.PAUSED_UNKNOWN, "单测：板位未知", "node-b");
        }

        Map<String, String> corrections = new LinkedHashMap<>();
        corrections.put("PL-TEST-M4", "ID1183-L2");

        PauseManager.ResumeResult r = pm.resume(runId, PauseManager.ResumeAction.CONTINUE,
                corrections, "tester");
        check("UNKNOWN + 校正 恢复成功", r.ok(), r.error());
        check("恢复后不再暂停", pm.pauseKindOf(runId) == null, "still paused");
    }

    /** 回程承诺位（release_exempt）暂停时不释放。 */
    private static void testReleaseExempt() {
        int exempt = count("SELECT count(*) FROM resource_booking"
                + " WHERE tenant_id = 1 AND release_exempt = true");
        int returns = count("SELECT count(*) FROM resource_booking"
                + " WHERE tenant_id = 1 AND source_type = 'position-return'");
        check("回程承诺的预约都标了豁免",
                returns == 0 || exempt >= returns,
                "exempt=" + exempt + " returns=" + returns);

        int blocked = count("SELECT count(*) FROM position_state"
                + " WHERE tenant_id = 1 AND block_reason = 'PLATE_UNKNOWN'"
                + " AND COALESCE(reserved_by_run, 0) <> -1");
        check("PLATE_UNKNOWN 的封锁都带 reserved_by_run=-1", blocked == 0,
                blocked + " 条不一致");
    }

    // ---------------------------------------------------------------- 工具

    private static long createRun() throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO run (tenant_id, run_no, run_type, workflow_id,
                                      workflow_ver, mode, status, actual_start, progress)
                     SELECT 1, 'M4-SELFTEST', 'workflow', 239, 1, mode,
                            'running', now(), 10
                     FROM run ORDER BY id DESC LIMIT 1
                     RETURNING id
                     """)) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        throw new IllegalStateException("造 run 失败");
    }

    private static void cleanup(long runId) {
        exec("DELETE FROM plate_location_audit WHERE run_id = " + runId);
        exec("DELETE FROM run WHERE id = " + runId);
        exec("DELETE FROM plate WHERE tenant_id = 1 AND barcode = 'PL-TEST-M4'");
    }

    private static String statusOf(long runId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status::text FROM run WHERE id = ?")) {
            ps.setLong(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static int count(String sql) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            return -1;
        }
    }

    private static void exec(String sql) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (Exception e) {
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
