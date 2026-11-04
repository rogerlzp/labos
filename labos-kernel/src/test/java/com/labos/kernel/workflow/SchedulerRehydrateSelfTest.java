package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * S3.2-A 重启 rehydrate 连库自测（§11.3-2）。
 *
 * <p>验证 {@link ExperimentScheduler#rehydrateQueued()}：
 * <ol>
 *   <li>库里 {@code status='queued'} 的 experiment_run 被枚举并入内存队列；</li>
 *   <li>{@code running/paused} 不被枚举（它们不是"待派发"，不能重复执行/提前收尾）；</li>
 *   <li>已在内存队列里的 run 不重复入队（幂等）；重复调用返回 0；</li>
 *   <li>新调度器实例（模拟进程重启）能把队列重新装满。</li>
 * </ol>
 *
 * <p>用合成负数 id 造数（与真实自增 id 不可能撞），finally 里按负数 id 精确清干净。
 * 不启动调度循环（{@code start()} 不调用），所以不会派发、不碰设备。
 */
public final class SchedulerRehydrateSelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;
    private static final long TENANT = 1L;

    // 合成负数 id：experiment 在 -993030，run 在 -993301..（与其它自测类不重叠）
    private static final long EXP_RH = -993030L;
    private static final long RUN_QUEUED_1 = -993301L;
    private static final long RUN_QUEUED_2 = -993302L;
    private static final long RUN_RUNNING = -993303L;
    private static final long RUN_PAUSED = -993304L;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();
        try {
            seed();
            rehydrateEnqueuesQueuedOnly();
            rehydrateIdempotent();
            freshSchedulerRefillsQueue();
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

    /** 已有一个 queued run 在内存队列（模拟"重启前已入队"的重复入队风险）。 */
    private static void rehydrateEnqueuesQueuedOnly() throws Exception {
        section("1. rehydrate 枚举 queued、跳过 running/paused、已在队列不重复");
        ExperimentScheduler scheduler = new ExperimentScheduler(db, TENANT, null);
        // 先手动把 RUN_QUEUED_1 放进内存队列：rehydrate 必须跳过它（幂等口）
        scheduler.resumeRun(EXP_RH, RUN_QUEUED_1);
        int queuedInDb = countQueuedRuns();

        int added = scheduler.rehydrateQueued();
        check("只补入未在队列的 queued run（库中 " + queuedInDb + " 条，已有 1 条）",
                added == queuedInDb - 1, added);
        List<Long> queueRuns = queueRunIds(scheduler);
        check("queued run#1 在队列", queueRuns.contains(RUN_QUEUED_1), queueRuns);
        check("queued run#2 在队列（被 rehydrate 补入）", queueRuns.contains(RUN_QUEUED_2),
                queueRuns);
        check("running run 没有被枚举", !queueRuns.contains(RUN_RUNNING), queueRuns);
        check("paused run 没有被枚举", !queueRuns.contains(RUN_PAUSED), queueRuns);
        check("队列总条数 = 库中 queued 条数（无重复）",
                queueRuns.size() == queuedInDb, queueRuns.size() + " vs " + queuedInDb);

        // 入队字段与 submit 同源：priority 来自 experiment（默认 5）、未分配岛=0
        ExperimentScheduler.QueueEntry entry = queueEntry(scheduler, RUN_QUEUED_2);
        check("入队项带 priority=实验默认值", entry != null && entry.priority() == 5,
                entry == null ? null : entry.priority());
        check("入队项带 submittedAt（now）", entry != null && entry.submittedAt() != null,
                entry == null ? null : entry.submittedAt());
    }

    /** 同一调度器重复调用：不重复入队，返回 0。 */
    private static void rehydrateIdempotent() throws Exception {
        section("2. 重复调用幂等（运行中不该反复 rehydrate）");
        ExperimentScheduler scheduler = new ExperimentScheduler(db, TENANT, null);
        int first = scheduler.rehydrateQueued();
        int sizeAfterFirst = queueRunIds(scheduler).size();
        int second = scheduler.rehydrateQueued();
        check("首次 rehydrate 入队全部 queued（" + first + " 条）", first >= 1, first);
        check("重复调用返回 0（不重复入队）", second == 0, second);
        check("重复调用后队列条数不变",
                queueRunIds(scheduler).size() == sizeAfterFirst,
                queueRunIds(scheduler).size() + " vs " + sizeAfterFirst);
    }

    /** 新调度器实例（=进程重启）能把库里 queued 的 run 重新装满内存队列。 */
    private static void freshSchedulerRefillsQueue() throws Exception {
        section("3. 重启（新调度器实例）rehydrate 恢复待派发队列");
        int queuedInDb = countQueuedRuns();
        ExperimentScheduler restarted = new ExperimentScheduler(db, TENANT, null);
        int added = restarted.rehydrateQueued();
        check("重启后 rehydrate 条数 = 库中 queued 条数", added == queuedInDb,
                added + " vs " + queuedInDb);
        List<Long> queueRuns = queueRunIds(restarted);
        check("重启后队列里有两条合成 queued run",
                queueRuns.contains(RUN_QUEUED_1) && queueRuns.contains(RUN_QUEUED_2),
                queueRuns);
        check("重启后 queue=2（running/paused 不在内）", queueRuns.size() == 2, queueRuns);
    }

    // ================================================================ 造数

    private static void seed() throws SQLException {
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO experiment (id, tenant_id, experiment_no, name, status)
                    VALUES (?, ?, 'SELF-S32-RH', 'SELF-S32-RH', 'created')
                    """)) {
                ps.setLong(1, EXP_RH);
                ps.setLong(2, TENANT);
                ps.executeUpdate();
            }
            insertRun(c, RUN_QUEUED_1, "queued");
            insertRun(c, RUN_QUEUED_2, "queued");
            insertRun(c, RUN_RUNNING, "running");
            insertRun(c, RUN_PAUSED, "paused");
        }
    }

    private static void insertRun(Connection c, long runId, String status) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO experiment_run (id, tenant_id, experiment_id, status)
                VALUES (?, ?, ?, ?)
                """)) {
            ps.setLong(1, runId);
            ps.setLong(2, TENANT);
            ps.setLong(3, EXP_RH);
            ps.setString(4, status);
            ps.executeUpdate();
        }
    }

    // ================================================================ 查询

    /** 库里（本租户）status='queued' 的 run 总数。 */
    private static int countQueuedRuns() throws SQLException {
        return count("SELECT count(*) FROM experiment_run WHERE tenant_id = ? AND status = 'queued'",
                TENANT);
    }

    private static List<Long> queueRunIds(ExperimentScheduler scheduler) {
        List<Long> ids = new ArrayList<>();
        for (ExperimentScheduler.QueueEntry q : scheduler.snapshot().queue()) {
            ids.add(q.experimentRunId());
        }
        return ids;
    }

    private static ExperimentScheduler.QueueEntry queueEntry(ExperimentScheduler scheduler,
                                                             long runId) {
        for (ExperimentScheduler.QueueEntry q : scheduler.snapshot().queue()) {
            if (q.experimentRunId() == runId) {
                return q;
            }
        }
        return null;
    }

    private static int count(String sql, Object... args) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // ================================================================ 清理

    private static void cleanup() throws SQLException {
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try {
                exec(c, "DELETE FROM experiment_run WHERE tenant_id = ? AND experiment_id IN ("
                        + EXP_RH + ")", TENANT);
                exec(c, "DELETE FROM experiment WHERE tenant_id = ? AND id IN ("
                        + EXP_RH + ")", TENANT);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
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

    private SchedulerRehydrateSelfTest() {
    }
}
