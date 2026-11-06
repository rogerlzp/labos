package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * BUG-14 启动兜底 settle 的连库自测。
 *
 * <p>覆盖口径：
 * <ol>
 *   <li><b>非批次孤儿 running</b>：settle 后 run 落 failed、error 写说明、
 *       finished_at 落库，experiment 表同步成 failed（与执行器同款映射）；</li>
 *   <li><b>批次孤儿 running</b>：settle 后 run failed，experiment_batch_run 同步
 *       failed + finished_at + error，批次汇总 failed，实验按 D6 聚合 failed；</li>
 *   <li><b>不触碰合法数据</b>：不在筛选范围内的 running 原样保留，
 *       已 completed 的 run / 实验一个字不动；重复 settle 幂等（第二次 0 条）。</li>
 * </ol>
 *
 * <p><b>为什么用包内筛选重载</b>：生产入口是全量 settle。自测若跑全量，
 * 会顺手把库里真实遗留的 running 收掉，重启日志就证明不了 BUG-14 的修复效果；
 * 所以自测用 {@link StartupRecovery#settleOrphanExperimentRuns(DbConfig, long,
 * java.util.Collection)} 只处理自己造的负数 id 数据，<b>finally 清干净</b>。
 */
public final class StartupSettleSelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;
    private static final long TENANT = 1L;

    // 合成负数 id：与真实自增 id 不可能撞，finally 里按这几组清干净
    private static final long EXP_PLAIN = -993030L;    // 非批次孤儿 running
    private static final long EXP_BATCH = -993031L;    // 批次孤儿 running（batch_split=1）
    private static final long EXP_KEEP = -993032L;     // 合法已完成数据（不许动）
    private static final long EXP_OUTSIDE = -993033L;  // 不在筛选范围的 running（不许动）
    private static final long SAMPLE_BATCH = -993140L;
    private static final long SAMPLE_KEEP = -993141L;

    /** 合成资源节点（device_state 的 FK 目标）与它持有的设备锁。 */
    private static final long RES_LOCK = -993900L;
    private static final String RES_LOCK_CODE = "SELFTEST-RES-993900";
    /** 孤儿锁用的第二个资源节点。 */
    private static final long RES_LOCK_ORPHAN = -993901L;
    private static final String RES_LOCK_ORPHAN_CODE = "SELFTEST-RES-993901";

    /** workflow 级的 run（设备锁 device_state.current_run_id 挂的是**这个** id 空间）。 */
    private static final long WF_RUN_LOCK = -993401L;

    /** 指向「已不存在的 run」的孤儿设备锁（run 被直接删掉时留下）。 */
    private static final long WF_RUN_GONE = -993402L;

    private static final long RUN_PLAIN = -993301L;
    private static final long RUN_BATCH = -993302L;
    private static final long RUN_KEEP = -993303L;
    private static final long RUN_OUTSIDE = -993304L;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();
        try {
            seed();
            settleNonBatchOrphan();
            settleBatchOrphan();
            idempotentAndDoesNotTouchOthers();
            releasesGhostDeviceLock();
            releasesOrphanLockWithoutOrphanRuns();
        } finally {
            cleanup();
        }

        System.out.println();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ================================================================ 用例

    /** 非批次 run：落 failed + error + finished_at，experiment 同步 failed。 */
    private static void settleNonBatchOrphan() throws Exception {
        section("1. 非批次孤儿 running -> failed + 实验状态同步");
        // 先记录当前所有 running（真实遗留的也算）：settle 后只允许少掉我们造的 2 条
        Set<Long> runningBefore = runningRunIds();

        List<StartupRecovery.SettledExperimentRun> settled =
                StartupRecovery.settleOrphanExperimentRuns(
                        db, TENANT, List.of(EXP_PLAIN, EXP_BATCH));
        check("只 settle 筛选范围内的 2 条", settled.size() == 2, settled.size());
        check("返回列表带非批次 run", containsRun(settled, RUN_PLAIN),
                settled.stream().map(StartupRecovery.SettledExperimentRun::runId).toList());
        check("返回列表带批次 run", containsRun(settled, RUN_BATCH),
                settled.stream().map(StartupRecovery.SettledExperimentRun::runId).toList());
        check("返回项带实验编号（按 runId 找到非批次那条）",
                "SELF-SETTLE-PLAIN".equals(experimentNoOf(settled, RUN_PLAIN)),
                experimentNoOf(settled, RUN_PLAIN));
        check("非批次 run 不被误判成批次",
                !batchRunFlagOf(settled, RUN_PLAIN), batchRunFlagOf(settled, RUN_PLAIN));

        check("run 落 failed", "failed".equals(runCol(RUN_PLAIN, "status")),
                runCol(RUN_PLAIN, "status"));
        check("error 写启动兜底说明",
                StartupRecovery.STARTUP_SETTLE_ERROR.equals(runCol(RUN_PLAIN, "error")),
                runCol(RUN_PLAIN, "error"));
        check("finished_at 落库（原来为空）", runCol(RUN_PLAIN, "finished_at") != null,
                runCol(RUN_PLAIN, "finished_at"));
        check("experiment 同步 failed",
                "failed".equals(experimentCol(EXP_PLAIN, "status")),
                experimentCol(EXP_PLAIN, "status"));
        check("experiment finished_at 同步", experimentCol(EXP_PLAIN, "finished_at") != null,
                experimentCol(EXP_PLAIN, "finished_at"));
        // 对比"settle 前 / settle 后"的全局 running 集合：只允许少掉我们造的 2 条，
        // 库里真实遗留的（若有，例如 EXP0812 的 run#2/#3）一条都不许被筛选重载碰掉。
        Set<Long> expectedAfter = new LinkedHashSet<>(runningBefore);
        expectedAfter.remove(RUN_PLAIN);
        expectedAfter.remove(RUN_BATCH);
        check("running 集合只少了我们造的 2 条",
                expectedAfter.equals(runningRunIds()), runningRunIds());
    }

    /** 批次 run：attempt + 批次汇总 + 实验聚合三层都要跟着落。 */
    private static void settleBatchOrphan() throws Exception {
        section("2. 批次孤儿 running -> failed + attempt/批次/实验三层同步");
        check("批次 run 落 failed", "failed".equals(runCol(RUN_BATCH, "status")),
                runCol(RUN_BATCH, "status"));
        check("批次 run error 有值",
                StartupRecovery.STARTUP_SETTLE_ERROR.equals(runCol(RUN_BATCH, "error")),
                runCol(RUN_BATCH, "error"));
        check("attempt 行同步 failed",
                "failed".equals(attemptCol(RUN_BATCH, "status")),
                attemptCol(RUN_BATCH, "status"));
        check("attempt 行带 error",
                StartupRecovery.STARTUP_SETTLE_ERROR.equals(attemptCol(RUN_BATCH, "error")),
                attemptCol(RUN_BATCH, "error"));
        check("attempt finished_at 落库", attemptCol(RUN_BATCH, "finished_at") != null,
                attemptCol(RUN_BATCH, "finished_at"));
        check("批次汇总 failed", "failed".equals(batchStatus(EXP_BATCH)),
                batchStatus(EXP_BATCH));
        check("实验按 D6 聚合 failed",
                "failed".equals(experimentCol(EXP_BATCH, "status")),
                experimentCol(EXP_BATCH, "status"));
        check("实验 finished_at 落库", experimentCol(EXP_BATCH, "finished_at") != null,
                experimentCol(EXP_BATCH, "finished_at"));
    }

    /** 幂等：第二次 0 条；合法数据（completed run / 筛选外 running）一个字不动。 */
    private static void idempotentAndDoesNotTouchOthers() throws Exception {
        section("3. 幂等 + 不触碰合法数据");
        List<StartupRecovery.SettledExperimentRun> again =
                StartupRecovery.settleOrphanExperimentRuns(
                        db, TENANT, List.of(EXP_PLAIN, EXP_BATCH));
        check("重复 settle 幂等（第二次 0 条）", again.isEmpty(), again.size());

        check("已完成 run 保持 completed", "completed".equals(runCol(RUN_KEEP, "status")),
                runCol(RUN_KEEP, "status"));
        check("已完成 run error 没有被写", runCol(RUN_KEEP, "error") == null,
                runCol(RUN_KEEP, "error"));
        check("筛选外 running 原样保留", "running".equals(runCol(RUN_OUTSIDE, "status")),
                runCol(RUN_OUTSIDE, "status"));
        check("筛选外实验状态没被改",
                "running".equals(experimentCol(EXP_OUTSIDE, "status")),
                experimentCol(EXP_OUTSIDE, "status"));
        check("合法实验保持 finished",
                "finished".equals(experimentCol(EXP_KEEP, "status")),
                experimentCol(EXP_KEEP, "status"));
        check("空筛选集合 -> 0 条（防御分支）",
                StartupRecovery.settleOrphanExperimentRuns(db, TENANT, List.of()).isEmpty(),
                "non-empty");
    }

    /**
     * 孤儿设备锁：**即使本次没有要 settle 的孤儿 run**，也必须被清。
     *
     * <p>这是踩过的坑：孤儿锁的清理一度放在
     * {@code if (found.isEmpty()) return orphans;} 的**后面** ——
     * 于是「本次没有孤儿 run」（最常见的情况）时，孤儿锁永远清不掉，
     * 那台设备就一直被占着。实测：机械臂的锁指向一个已被删除的 run，
     * 重启后仍然占着 ⇒ 设备又拿不到。
     */
    private static void releasesOrphanLockWithoutOrphanRuns() throws Exception {
        section("5. 没有孤儿 run 时，孤儿设备锁也要被清");
        try (Connection c = db.open()) {
            // 重新造一把孤儿锁
            exec(c, "INSERT INTO device_state (tenant_id, resource_id, status, current_run_id, "
                    + "current_method, online) VALUES (?, ?, 'running', ?, 'Home', true) "
                    + "ON CONFLICT (tenant_id, resource_id) DO UPDATE "
                    + "SET current_run_id = EXCLUDED.current_run_id, "
                    + "    current_method = EXCLUDED.current_method, "
                    + "    status = 'running', online = true",
                    TENANT, RES_LOCK_ORPHAN, WF_RUN_GONE);
        }
        // 限定一个**不存在的 run id** ⇒ found 为空 ⇒ 走提前返回那条路
        StartupRecovery.settle(db, TENANT, List.of(-993499L));
        try (Connection c = db.open()) {
            String owner = scalar(c,
                    "SELECT COALESCE(current_run_id::text, '(null)') FROM device_state "
                            + "WHERE tenant_id = ? AND resource_id = ?",
                    TENANT, RES_LOCK_ORPHAN);
            check("提前返回时孤儿设备锁也被释放", "(null)".equals(owner), owner);
        }
    }

    /**
     * 设备锁必须被释放（幽灵占用）。
     *
     * <p>没有这条：进程崩溃 / API 重启时正在执行的设备方法不会走 onStepDone
     * ⇒ markDeviceIdle 永远不执行 ⇒ device_state 永远停在
     * {@code status='running' + current_run_id=<已死的 run>}，
     * 而 {@code DeviceLock.tryAcquire} 的条件是
     * {@code current_run_id IS NULL OR current_run_id = 自己}
     * ⇒ 这台设备**从此再也不会被任何 run 拿到**（现场表现：一台设备凭空永远忙）。
     */
    private static void releasesGhostDeviceLock() throws Exception {
        section("4. 启动兜底 settle 释放幽灵设备锁");
        // 只处理我们造的那条孤儿 run（别把库里真实的遗留 running 一起收了）
        StartupRecovery.settle(db, TENANT, List.of(WF_RUN_LOCK));
        try (Connection c = db.open()) {
            String owner = scalar(c,
                    "SELECT COALESCE(current_run_id::text, '(null)') FROM device_state "
                            + "WHERE tenant_id = ? AND resource_id = ?", TENANT, RES_LOCK);
            check("settle 后设备锁的 current_run_id 已清空", "(null)".equals(owner), owner);
            String st = scalar(c,
                    "SELECT status FROM device_state WHERE tenant_id = ? AND resource_id = ?",
                    TENANT, RES_LOCK);
            check("settle 后设备状态回到 idle", "idle".equals(st), st);
            String m = scalar(c,
                    "SELECT COALESCE(current_method, '(null)') FROM device_state "
                            + "WHERE tenant_id = ? AND resource_id = ?", TENANT, RES_LOCK);
            check("settle 后设备的 current_method 已清空", "(null)".equals(m), m);
            // 孤儿锁（指向已删除的 run）：与本次 settle 无关，也必须被清
            String owner2 = scalar(c,
                    "SELECT COALESCE(current_run_id::text, '(null)') FROM device_state "
                            + "WHERE tenant_id = ? AND resource_id = ?",
                    TENANT, RES_LOCK_ORPHAN);
            check("孤儿设备锁（指向已删 run）也被释放", "(null)".equals(owner2), owner2);
            String st2 = scalar(c,
                    "SELECT status FROM device_state WHERE tenant_id = ? AND resource_id = ?",
                    TENANT, RES_LOCK_ORPHAN);
            check("孤儿设备锁的状态回到 idle", "idle".equals(st2), st2);
        }
    }

    /** 读一列值（取第一行）。 */
    private static String scalar(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // ================================================================ 造数

    private static void seed() throws SQLException {
        try (Connection c = db.open()) {
            insertExperiment(c, EXP_PLAIN, "SELF-SETTLE-PLAIN", null, "running");
            insertExperiment(c, EXP_BATCH, "SELF-SETTLE-BATCH", 1, "running");
            insertExperiment(c, EXP_KEEP, "SELF-SETTLE-KEEP", null, "finished");
            insertExperiment(c, EXP_OUTSIDE, "SELF-SETTLE-OUTSIDE", null, "running");

            insertSample(c, SAMPLE_BATCH, "BC-SETTLE-BATCH");
            linkSample(c, EXP_BATCH, SAMPLE_BATCH);
            insertSample(c, SAMPLE_KEEP, "BC-SETTLE-KEEP");
            linkSample(c, EXP_KEEP, SAMPLE_KEEP);

            // 非批次孤儿：started_at 为空（仿库里 run#2 的历史形态）
            insertRunRow(c, RUN_PLAIN, EXP_PLAIN, "running", null, null);
            // 批次孤儿：batch 状态 running + attempt running，模拟"进程崩溃时正在跑"
            BatchOrchestrator orch = new BatchOrchestrator(db, TENANT);
            long batchId = orch.ensureBatches(EXP_BATCH).get(0).id();
            insertRunRow(c, RUN_BATCH, EXP_BATCH, "running", null, "now()");
            insertAttempt(c, batchId, RUN_BATCH, 1, "running");
            exec(c, "UPDATE experiment_batch SET status = 'running' "
                    + "WHERE tenant_id = ? AND id = ?", TENANT, batchId);
            // 设备锁：幽灵占用（current_run_id 指向将被 settle 的孤儿 run）
            // —— 模拟「进程崩溃 / API 重启时设备方法还在执行」的后果
            exec(c, "INSERT INTO resource_node (id, tenant_id, code, name, kind, capacity) "
                    + "VALUES (?, ?, ?, ?, 'device', 1) ON CONFLICT (id) DO NOTHING",
                    RES_LOCK, TENANT, RES_LOCK_CODE, "自测资源");
            exec(c, "INSERT INTO resource_node (id, tenant_id, code, name, kind, capacity) "
                    + "VALUES (?, ?, ?, ?, 'device', 1) ON CONFLICT (id) DO NOTHING",
                    RES_LOCK_ORPHAN, TENANT, RES_LOCK_ORPHAN_CODE, "自测资源-孤儿锁");
            // workflow 级的孤儿 run（设备锁挂的就是这个 id 空间）
            exec(c, "INSERT INTO run (id, tenant_id, run_no, run_type, status) "
                    + "VALUES (?, ?, ?, 'workflow', 'running') ON CONFLICT (id) DO NOTHING",
                    WF_RUN_LOCK, TENANT, "SELFTEST-WF-993401");
            exec(c, "INSERT INTO device_state (tenant_id, resource_id, status, current_run_id, "
                    + "current_method, online) VALUES (?, ?, 'running', ?, 'Home', true) "
                    + "ON CONFLICT (tenant_id, resource_id) DO UPDATE "
                    + "SET current_run_id = EXCLUDED.current_run_id, "
                    + "    current_method = EXCLUDED.current_method, "
                    + "    status = 'running', online = true",
                    TENANT, RES_LOCK, WF_RUN_LOCK);
            // 孤儿锁：指向一个**根本不存在**的 run（模拟 run 被直接删掉）
            exec(c, "INSERT INTO device_state (tenant_id, resource_id, status, current_run_id, "
                    + "current_method, online) VALUES (?, ?, 'running', ?, 'Home', true) "
                    + "ON CONFLICT (tenant_id, resource_id) DO UPDATE "
                    + "SET current_run_id = EXCLUDED.current_run_id, "
                    + "    current_method = EXCLUDED.current_method, "
                    + "    status = 'running', online = true",
                    TENANT, RES_LOCK_ORPHAN, WF_RUN_GONE);

            // 合法已完成：run + 实验
            insertRunRow(c, RUN_KEEP, EXP_KEEP, "completed", null, "now()");
            exec(c, "UPDATE experiment_run SET finished_at = now() "
                    + "WHERE tenant_id = ? AND id = ?", TENANT, RUN_KEEP);
            // 筛选范围外的 orphan running
            insertRunRow(c, RUN_OUTSIDE, EXP_OUTSIDE, "running", null, "now()");
        }
    }

    private static void insertExperiment(Connection c, long id, String no, Integer batchSplit,
                                         String status) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO experiment
                    (id, tenant_id, experiment_no, name, status, batch_split, started_at)
                VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ? = 'running' THEN now() END)
                """)) {
            ps.setLong(1, id);
            ps.setLong(2, TENANT);
            ps.setString(3, no);
            ps.setString(4, no);
            ps.setString(5, status);
            if (batchSplit == null) {
                ps.setNull(6, Types.INTEGER);
            } else {
                ps.setInt(6, batchSplit);
            }
            ps.setString(7, status);
            ps.executeUpdate();
        }
    }

    private static void insertSample(Connection c, long id, String barcode) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO sample (id, tenant_id, barcode, name, sample_type)
                VALUES (?, ?, ?, ?, 'serum')
                """)) {
            ps.setLong(1, id);
            ps.setLong(2, TENANT);
            ps.setString(3, barcode);
            ps.setString(4, "自测SETTLE-" + barcode);
            ps.executeUpdate();
        }
    }

    private static void linkSample(Connection c, long experimentId, long sampleId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO experiment_sample (experiment_id, sample_id, sample_role)
                VALUES (?, ?, 'test')
                """)) {
            ps.setLong(1, experimentId);
            ps.setLong(2, sampleId);
            ps.executeUpdate();
        }
    }

    private static void insertRunRow(Connection c, long runId, long experimentId,
                                     String status, String error, String startedAtLiteral)
            throws SQLException {
        // 小工具：started_at 允许传 "now()" / null，避免为两种形态写两遍 SQL
        String started = startedAtLiteral == null ? "NULL" : startedAtLiteral;
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO experiment_run
                    (id, tenant_id, experiment_id, status, error, started_at)
                VALUES (?, ?, ?, ?, ?,
                        CASE WHEN ? = 'now()' THEN now() ELSE NULL END)
                """)) {
            ps.setLong(1, runId);
            ps.setLong(2, TENANT);
            ps.setLong(3, experimentId);
            ps.setString(4, status);
            ps.setString(5, error);
            ps.setString(6, startedAtLiteral);
            ps.executeUpdate();
        }
    }

    private static void insertAttempt(Connection c, long batchId, long runId, int attemptNo,
                                      String status) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO experiment_batch_run
                    (tenant_id, batch_id, experiment_run_id, attempt_no, status, started_at)
                VALUES (?, ?, ?, ?, ?, now())
                """)) {
            ps.setLong(1, TENANT);
            ps.setLong(2, batchId);
            ps.setLong(3, runId);
            ps.setInt(4, attemptNo);
            ps.setString(5, status);
            ps.executeUpdate();
        }
    }

    // ================================================================ 查询

    private static Set<Long> runningRunIds() throws SQLException {
        Set<Long> out = new LinkedHashSet<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id FROM experiment_run WHERE tenant_id = ? AND status = 'running' "
                             + "ORDER BY id")) {
            ps.setLong(1, TENANT);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getLong(1));
                }
            }
        }
        return out;
    }

    private static boolean containsRun(List<StartupRecovery.SettledExperimentRun> list, long runId) {
        return list.stream().anyMatch(s -> s.runId() == runId);
    }

    private static String experimentNoOf(
            List<StartupRecovery.SettledExperimentRun> list, long runId) {
        return list.stream().filter(s -> s.runId() == runId)
                .map(StartupRecovery.SettledExperimentRun::experimentNo)
                .findFirst().orElse(null);
    }

    private static boolean batchRunFlagOf(
            List<StartupRecovery.SettledExperimentRun> list, long runId) {
        return list.stream().filter(s -> s.runId() == runId)
                .map(StartupRecovery.SettledExperimentRun::batchRun)
                .findFirst().orElse(false);
    }

    private static String runCol(long runId, String column) throws SQLException {
        return str("SELECT " + column + " FROM experiment_run "
                + "WHERE tenant_id = ? AND id = ?", TENANT, runId);
    }

    private static String attemptCol(long runId, String column) throws SQLException {
        return str("SELECT " + column + " FROM experiment_batch_run "
                + "WHERE tenant_id = ? AND experiment_run_id = ? ORDER BY id DESC LIMIT 1",
                TENANT, runId);
    }

    private static String batchStatus(long experimentId) throws SQLException {
        return str("SELECT status FROM experiment_batch "
                + "WHERE tenant_id = ? AND experiment_id = ? ORDER BY batch_no LIMIT 1",
                TENANT, experimentId);
    }

    private static String experimentCol(long experimentId, String column) throws SQLException {
        return str("SELECT " + column + " FROM experiment WHERE tenant_id = ? AND id = ?",
                TENANT, experimentId);
    }

    private static String str(String sql, Object... args) throws SQLException {
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

    private static void exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    // ================================================================ 清理

    private static void cleanup() throws SQLException {
        // 合成资源节点 + 设备锁（FK 是 ON DELETE CASCADE，删资源节点即可带走设备状态）
        try (Connection c = db.open()) {
            exec(c, "DELETE FROM resource_node WHERE id IN (?, ?)",
                    RES_LOCK, RES_LOCK_ORPHAN);
            exec(c, "DELETE FROM run WHERE id = ?", WF_RUN_LOCK);
        }
        String expIn = EXP_PLAIN + "," + EXP_BATCH + "," + EXP_KEEP + "," + EXP_OUTSIDE;
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try {
                exec(c, "DELETE FROM experiment_batch_run WHERE tenant_id = ? AND batch_id IN ("
                        + "SELECT id FROM experiment_batch WHERE tenant_id = ? "
                        + "AND experiment_id IN (" + expIn + "))", TENANT, TENANT);
                exec(c, "DELETE FROM experiment_batch WHERE tenant_id = ? AND experiment_id IN ("
                        + expIn + ")", TENANT);
                exec(c, "DELETE FROM experiment_run_step WHERE tenant_id = ? "
                        + "AND experiment_run_id IN (SELECT id FROM experiment_run "
                        + "WHERE tenant_id = ? AND experiment_id IN (" + expIn + "))",
                        TENANT, TENANT);
                exec(c, "DELETE FROM experiment_run WHERE tenant_id = ? AND experiment_id IN ("
                        + expIn + ")", TENANT);
                exec(c, "DELETE FROM experiment_sample WHERE experiment_id IN (" + expIn + ")");
                exec(c, "DELETE FROM experiment WHERE tenant_id = ? AND id IN (" + expIn + ")",
                        TENANT);
                exec(c, "DELETE FROM sample WHERE tenant_id = ? AND id IN ("
                        + SAMPLE_BATCH + "," + SAMPLE_KEEP + ")", TENANT);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    // ================================================================ 断言

    private static void check(String name, boolean ok, Object actual) {
        if (ok) {
            pass++;
            System.out.printf("  ✓ %-46s %s%n", name, actual);
        } else {
            fail++;
            System.out.printf("  ✗ %-46s 实际 %s%n", name, actual);
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("── " + title + " ──");
    }

    private StartupSettleSelfTest() {
    }
}
