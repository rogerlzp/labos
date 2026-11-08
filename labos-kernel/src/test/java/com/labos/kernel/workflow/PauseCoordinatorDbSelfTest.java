package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * 暂停统一适配层 幂等键 落库自测（需要真实库）—— V1-5 / ADR-PAUSE-009。
 *
 * <p>{@code PauseCoordinator} 在请求带 {@code X-Request-Id} 时按 {@code pause_request}
 * 表去重。这里压三条此前<b>零测试</b>的路径：
 * <ol>
 *   <li><b>同 requestId 重复暂停</b>：第二次必须回放首次结果，<b>不再抬第二道扣留</b>。
 *       走真代码路径断言 {@code pause_request} 只有 1 行、{@code runtime_hold} 生效数不增
 *       —— 只断言"返回相同"是不够的，重复抬 Hold 才是真缺陷。</li>
 *   <li><b>无 requestId 不去重</b>：旧客户端（不带该头）必须与改动前逐字段一致：
 *       {@code pause_request} 一行都不写。</li>
 *   <li><b>失败可重跑</b>：目标 run 不存在时首次执行抛错，{@code abandon} 撤回登记，
 *       带同一 requestId 重试能真正重跑，而不是被回放成一个「从未执行过」的请求。</li>
 * </ol>
 *
 * <p>用合成负数 id（与真实自增 id 不可能撞）并在 finally 里清干净。
 * <b>不用 site 范围</b> —— 全场闸门会把正在跑的实验一起停掉，自测不能这么干。
 */
public final class PauseCoordinatorDbSelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;
    private static final long TENANT = 1L;

    private static final long EXP_P = -993010L;    // 幂等暂停用
    private static final long RUN_P = -993101L;    // EXP_P，running
    private static final long RUN_NEW = -993102L;  // EXP_P，重跑用
    private static final long MISSING_RUN = -993999L;  // 库里没有这条 run

    private static final String REQ_PAUSE = "SELF-PC-PAUSE-1";
    private static final String REQ_RETRY = "SELF-PC-RETRY-1";

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();
        try {
            PauseCoordinator coord = new PauseCoordinator(db, TENANT, null, null);

            idempotentPauseReplays(coord);
            noRequestIdSkipsDedup(coord);
            failedAttemptCanBeRetried(coord);
        } finally {
            cleanup();
        }

        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 1. 同 requestId 幂等

    private static void idempotentPauseReplays(PauseCoordinator coord) throws Exception {
        seed();

        PauseCoordinator.ExperimentPause first = coord.pauseExperimentRun(
                PauseCommand.experiment(RUN_P, PauseCommand.Action.PAUSE, "manual",
                        "自测：首次暂停", REQ_PAUSE), "experiment");
        check("首次暂停落在实验级", RuntimeHold.Scope.EXPERIMENT.code().equals(first.scopeCode()),
                first.scopeCode());
        check("首次暂停生效后 run 为 paused", "paused".equals(first.status()), first.status());
        check("首次暂停登记了 1 条幂等键", pauseReqCount(REQ_PAUSE) == 1, pauseReqCount(REQ_PAUSE));
        check("首次暂停抬出 1 道扣留", holdCount(EXP_P) == 1, holdCount(EXP_P));

        PauseCoordinator.ExperimentPause again = coord.pauseExperimentRun(
                PauseCommand.experiment(RUN_P, PauseCommand.Action.PAUSE, "manual",
                        "自测：重复暂停", REQ_PAUSE), "experiment");
        check("重复暂停回放相同作用域", first.scopeCode().equals(again.scopeCode()), again.scopeCode());
        check("重复暂停回放相同状态", first.status().equals(again.status()), again.status());
        check("重复暂停回放相同扣留类型", first.kindCode().equals(again.kindCode()), again.kindCode());
        check("重复暂停不再新增幂等键", pauseReqCount(REQ_PAUSE) == 1, pauseReqCount(REQ_PAUSE));
        check("重复暂停不再抬第二道扣留（关键）", holdCount(EXP_P) == 1, holdCount(EXP_P));

        new RuntimeHold(db, TENANT).clear(RuntimeHold.Scope.EXPERIMENT, EXP_P, null, "自测：清扣留");
    }

    // ---------------------------------------------------------------- 2. 无 requestId 不去重

    private static void noRequestIdSkipsDedup(PauseCoordinator coord) throws Exception {
        setRunStatus(RUN_P, "running");

        coord.pauseExperimentRun(PauseCommand.experiment(RUN_P, PauseCommand.Action.PAUSE,
                "manual", "自测：旧客户端无幂等键", null), "experiment");

        check("无 requestId 时不写 pause_request", pauseReqCount(REQ_PAUSE) == 1, pauseReqCount(REQ_PAUSE));
        check("无 requestId 仍正常抬出扣留", holdCount(EXP_P) == 1, holdCount(EXP_P));

        new RuntimeHold(db, TENANT).clear(RuntimeHold.Scope.EXPERIMENT, EXP_P, null, "自测：清扣留");
    }

    // ---------------------------------------------------------------- 3. 失败可重跑

    private static void failedAttemptCanBeRetried(PauseCoordinator coord) throws Exception {
        boolean threw = false;
        try {
            // 目标 run 不存在：info() 抛 IllegalArgumentException，Coordinator 应 abandon 后再抛。
            coord.pauseExperimentRun(PauseCommand.experiment(MISSING_RUN, PauseCommand.Action.PAUSE,
                    "manual", "自测：目标不存在", REQ_RETRY), "experiment");
        } catch (RuntimeException e) {
            threw = true;
        }
        check("目标不存在时抛错", threw, threw);
        check("失败后幂等键被撤回（可重跑）", pauseReqCount(REQ_RETRY) == 0, pauseReqCount(REQ_RETRY));

        // 补一条真实 run，带同一 requestId 重试 —— 必须真正执行，而不是被回放。
        try (Connection c = db.open()) {
            insertRun(c, RUN_NEW, EXP_P, "running");
        }
        PauseCoordinator.ExperimentPause retry = coord.pauseExperimentRun(
                PauseCommand.experiment(RUN_NEW, PauseCommand.Action.PAUSE, "manual",
                        "自测：重跑", REQ_RETRY), "experiment");
        check("同 requestId 重试真正重跑", "paused".equals(retry.status()), retry.status());
        check("重跑后幂等键登记为 1 条", pauseReqCount(REQ_RETRY) == 1, pauseReqCount(REQ_RETRY));
        check("重跑抬出扣留", holdCount(EXP_P) == 1, holdCount(EXP_P));

        new RuntimeHold(db, TENANT).clear(RuntimeHold.Scope.EXPERIMENT, EXP_P, null, "自测：清扣留");
    }

    // ---------------------------------------------------------------- 造数 / 查询 / 清理

    private static void seed() throws SQLException {
        try (Connection c = db.open()) {
            insertExperiment(c);
            insertRun(c, RUN_P, EXP_P, "running");
        }
    }

    private static void insertExperiment(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO experiment (id, tenant_id, experiment_no, name, status, island_id)
                VALUES (?, ?, ?, ?, 'created', ?)
                """)) {
            ps.setLong(1, EXP_P);
            ps.setLong(2, TENANT);
            ps.setString(3, "SELF-PC-P");
            ps.setString(4, "SELF-PC-P");
            ps.setNull(5, Types.BIGINT);
            ps.executeUpdate();
        }
    }

    private static void insertRun(Connection c, long id, long experimentId, String status)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO experiment_run (id, tenant_id, experiment_id, status)
                VALUES (?, ?, ?, ?)
                """)) {
            ps.setLong(1, id);
            ps.setLong(2, TENANT);
            ps.setLong(3, experimentId);
            ps.setString(4, status);
            ps.executeUpdate();
        }
    }

    private static void setRunStatus(long runId, String status) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE experiment_run SET status = ? WHERE tenant_id = ? AND id = ?")) {
            ps.setString(1, status);
            ps.setLong(2, TENANT);
            ps.setLong(3, runId);
            ps.executeUpdate();
        }
    }

    /** 该 requestId 在 pause_request 里的行数（幂等键唯一性证据）。 */
    private static int pauseReqCount(String requestId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM pause_request WHERE tenant_id = ? AND request_id = ?")) {
            ps.setLong(1, TENANT);
            ps.setString(2, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** 该实验生效中的实验级扣留条数（重复抬 Hold 的反面证据）。 */
    private static int holdCount(long experimentId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT count(*) FROM runtime_hold
                      WHERE tenant_id = ? AND released_at IS NULL
                        AND scope = 'experiment' AND scope_id = ?
                     """)) {
            ps.setLong(1, TENANT);
            ps.setLong(2, experimentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** 删掉自测造的行（物理删除：自测数据不该留在现场闸门表 / 幂等表里）。 */
    private static void cleanup() throws SQLException {
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM pause_request WHERE tenant_id = ? AND request_id LIKE 'SELF-PC-%'")) {
                ps.setLong(1, TENANT);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM runtime_hold WHERE tenant_id = ? AND scope = 'experiment' AND scope_id = ?")) {
                ps.setLong(1, TENANT);
                ps.setLong(2, EXP_P);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM experiment_run WHERE tenant_id = ? AND id IN (?, ?)")) {
                ps.setLong(1, TENANT);
                ps.setLong(2, RUN_P);
                ps.setLong(3, RUN_NEW);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM experiment WHERE tenant_id = ? AND id = ?")) {
                ps.setLong(1, TENANT);
                ps.setLong(2, EXP_P);
                ps.executeUpdate();
            }
        }
    }

    private static void check(String name, boolean ok, Object actual) {
        if (ok) {
            pass++;
            System.out.printf("  ✓ %-32s %s%n", name, actual);
        } else {
            fail++;
            System.out.printf("  ✗ %-32s 实际 %s%n", name, actual);
        }
    }

    private PauseCoordinatorDbSelfTest() {
    }
}
