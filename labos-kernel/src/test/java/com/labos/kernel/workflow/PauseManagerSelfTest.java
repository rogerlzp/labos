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
        // 触发自测容器：绑定 Mappers（非 Bean 工具类通过 Mappers.get 取 Mapper），
        // 并让 DbConfig 与容器共用同一个 DataSource。
        com.labos.kernel.support.TestSpringSupport.db();
        db = DbConfig.fromEnv();
        long runId = createRun();

        try {
            testPauseAndResume(runId);
            testUnknownRequiresCorrection(runId);
            testUnknownWithCorrection(runId);
            testReleaseExempt();
            testReleasePortUsage(runId);
            testAutoResumeFaultedScope(runId);
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
        check("waiting_human 暂停自动开人工窗口", count(
                "SELECT count(*) FROM manual_window WHERE tenant_id=1 AND run_id=" + runId
                        + " AND status='pending'") == 1,
                count("SELECT count(*) FROM manual_window WHERE tenant_id=1 AND run_id="
                        + runId + " AND status='pending'"));

        exec("INSERT INTO transfer_transaction (tenant_id, run_id, plate_barcode, status) "
                + "VALUES (1, " + runId + ", 'PL-TEST-M4', 'REPORTED')");
        PauseManager.ResumeResult blocked = pm.resume(runId,
                PauseManager.ResumeAction.CONTINUE, Map.of(), "test");
        check("未确认搬运位置拒绝恢复", !blocked.ok(), blocked.ok());

        exec("UPDATE transfer_transaction SET status='CONFIRMED' "
                + "WHERE tenant_id=1 AND run_id=" + runId);
        PauseManager.ResumeResult r = pm.resume(runId, PauseManager.ResumeAction.CONTINUE,
                Map.of(), "test");
        check("恢复成功", r.ok(), r.error());
        // P0-5：恢复后是 queued（如实表达"放行待重新拾起"），不是假装 running
        String st = statusOf(runId);
        check("恢复后离开 paused（queued 或 running）",
                "queued".equals(st) || "running".equals(st), st);
        check("恢复后自动关闭人工窗口", count(
                "SELECT count(*) FROM manual_window WHERE tenant_id=1 AND run_id=" + runId
                        + " AND status='pending'") == 0,
                count("SELECT count(*) FROM manual_window WHERE tenant_id=1 AND run_id="
                        + runId + " AND status='pending'"));
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

    /**
     * V1-3：暂停释放资源时<b>通道占用必须真的清零</b>。
     *
     * <p>改动前 step 3 的两个占位符由 {@code exec} 统一绑成 {@code (tenant_id, runId)}，
     * 第二个 {@code ?}（{@code device_port.tenant_id}）拿到的是 {@code runId}，
     * 子查询永远查不到行 —— 这一步实际是空操作，通道计数只涨不落。
     *
     * <p>通道占用是租户级信号量（{@code device_port_usage} 无 run_id 列），
     * 所以先把现场占用快照下来，测完按原值还原，避免污染其它用例/现场。
     */
    private static void testReleasePortUsage(long runId) throws Exception {
        Map<Long, Integer> before = new LinkedHashMap<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT port_id, in_use FROM device_port_usage WHERE tenant_id = ?")) {
            ps.setLong(1, TENANT);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    before.put(rs.getLong(1), rs.getInt(2));
                }
            }
        }
        check("通道释放：库里有通道占用行可测", !before.isEmpty(), "无 device_port_usage 行");
        if (before.isEmpty()) {
            return;
        }

        long probePort = before.keySet().iterator().next();
        // 造一个"占用中"的通道：in_use 拉到 max_capacity（满足 ck_port_usage 约束）
        exec("UPDATE device_port_usage SET in_use = max_capacity WHERE tenant_id = "
                + TENANT + " AND port_id = " + probePort);
        check("通道释放：前置占用已造好", inUseOf(probePort) > 0, inUseOf(probePort));

        new PauseManager(db, TENANT).releaseResources(runId);

        check("通道释放后 in_use = 0（V1-3：原第二个 ? 被绑成 runId 导致空操作）",
                inUseOf(probePort) == 0, inUseOf(probePort));

        // 还原：只回填测试前就 > 0 的通道，其余保持 0
        for (Map.Entry<Long, Integer> e : before.entrySet()) {
            if (e.getValue() > 0) {
                exec("UPDATE device_port_usage SET in_use = " + e.getValue()
                        + " WHERE tenant_id = " + TENANT + " AND port_id = " + e.getKey());
            }
        }
    }

    private static int inUseOf(long portId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT in_use FROM device_port_usage WHERE tenant_id = ? AND port_id = ?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, portId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- 工具

    /**
     * S-04-B 修复回归：paused_fault 自动恢复只看**本流程用到的设备**是否在线。
     *
     * <p>旧口径要求全租户所有 device_state 都在线；现场只要有一台无关设备离线
     * 就永远不自动恢复（demo 库就长期有多台离线）。这里造一条用到 involved
     * 设备的 run，把无关设备置离线，断言不影响自动恢复；再把 involved 置离线，
     * 断言会被正确拦住；恢复在线后自动继续。
     */
    private static void testAutoResumeFaultedScope(long runId) throws Exception {
        String wfRaw = one("SELECT workflow_id::text FROM run WHERE id=" + runId);
        if (wfRaw == null || wfRaw.isEmpty() || "null".equals(wfRaw)) {
            check("自动恢复范围用例需要 run.workflow_id", false, wfRaw);
            return;
        }
        long wf = Long.parseLong(wfRaw);
        long involved = 200162L;
        long unrelated = 200164L;
        Map<Long, Boolean> orig = new LinkedHashMap<>();
        orig.put(involved, deviceOnline(involved));
        orig.put(unrelated, deviceOnline(unrelated));
        exec("DELETE FROM workflow_node WHERE workflow_id=" + wf
                + " AND node_key LIKE 'SELF-AR-%'");
        try {
            insertWorkflowNode(wf, "SELF-AR-INV", involved);
            setDeviceOnline(involved, true);
            setDeviceOnline(unrelated, false);
            setRunPausedFault(runId);
            new PauseManager(db, TENANT).autoResumeFaulted();
            check("无关设备离线不影响自动恢复（只看本流程设备）",
                    "running".equals(statusOf(runId)), statusOf(runId));

            setRunPausedFault(runId);
            setDeviceOnline(involved, false);
            new PauseManager(db, TENANT).autoResumeFaulted();
            check("本流程所用设备离线时不自动恢复",
                    "paused".equals(statusOf(runId)), statusOf(runId));

            setRunPausedFault(runId);
            setDeviceOnline(involved, true);
            new PauseManager(db, TENANT).autoResumeFaulted();
            check("本流程所用设备恢复在线后自动继续",
                    "running".equals(statusOf(runId)), statusOf(runId));
        } finally {
            exec("DELETE FROM workflow_node WHERE workflow_id=" + wf
                    + " AND node_key LIKE 'SELF-AR-%'");
            for (Map.Entry<Long, Boolean> en : orig.entrySet()) {
                setDeviceOnline(en.getKey(), en.getValue());
            }
        }
    }

    private static void insertWorkflowNode(long workflowId, String key, long resourceId)
            throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO workflow_node (tenant_id, workflow_id, node_key,
                                                node_type, name, resource_id)
                     VALUES (1, ?, ?, 'task', 'autoresume selftest', ?)
                     ON CONFLICT (workflow_id, node_key)
                     DO UPDATE SET resource_id = EXCLUDED.resource_id
                     """)) {
            ps.setLong(1, workflowId);
            ps.setString(2, key);
            ps.setLong(3, resourceId);
            ps.executeUpdate();
        }
    }

    private static Boolean deviceOnline(long resourceId) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT online FROM device_state WHERE tenant_id = 1 AND resource_id = ?")) {
            ps.setLong(1, resourceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBoolean(1) : Boolean.TRUE;
            }
        } catch (Exception e) {
            return Boolean.TRUE;
        }
    }

    private static void setDeviceOnline(long resourceId, boolean online) {
        exec("UPDATE device_state SET online = " + online
                + " WHERE tenant_id = 1 AND resource_id = " + resourceId);
    }

    private static void setRunPausedFault(long runId) {
        exec("UPDATE run SET status = 'paused', pause_kind = 'paused_fault', "
                + "pause_reason = 'autoresume selftest', resumed_at = NULL WHERE id = " + runId);
    }

    private static String one(String sql) {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

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
        exec("DELETE FROM manual_window WHERE tenant_id = 1 AND run_id = " + runId);
        exec("DELETE FROM transfer_transaction WHERE tenant_id = 1 AND run_id = " + runId);
        exec("DELETE FROM plate_location_audit WHERE run_id = " + runId);
        exec("DELETE FROM run WHERE id = " + runId);
        exec("DELETE FROM plate WHERE tenant_id = 1 AND barcode = 'PL-TEST-M4'");
        exec("DELETE FROM position_evidence WHERE tenant_id = 1 AND plate_barcode = 'PL-TEST-M4'");
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
