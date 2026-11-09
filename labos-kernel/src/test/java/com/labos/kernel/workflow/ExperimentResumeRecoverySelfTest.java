package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * FIX-P0-03：实验级 resume 在 commit 后的恢复闭环自测（需要真实库）。
 *
 * <p>覆盖三条必须口径：
 * <ol>
 *   <li>正常路径：scheduler 在场时，resume 后 run 立即入内存队列，control_state 回 NONE；</li>
 *   <li>异常路径：若现场 site Hold 仍覆盖，resume 不得假装入队，run 维持 paused；</li>
 *   <li>崩溃恢复：commit 成功但当时没 scheduler 时，run 先落 queued+RESUMING；重启 rehydrate
 *       必须把它装回队列并把 control_state 清回 NONE。</li>
 * </ol>
 */
public final class ExperimentResumeRecoverySelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;
    private static final long TENANT = 1L;
    private static final long EXP = -993050L;
    private static final long RUN = -993350L;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();
        try {
            seed();
            immediateSchedulerClearsResuming();
            blockedResumeStaysPaused();
            rehydrateRecoversQueuedResumingRun();
        } finally {
            cleanup();
        }
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void immediateSchedulerClearsResuming() throws Exception {
        resetPaused();
        ExperimentScheduler scheduler = new ExperimentScheduler(db, TENANT, null);
        PauseCoordinator coord = new PauseCoordinator(db, TENANT, null, scheduler);
        PauseCoordinator.ExperimentResume r = coord.resumeExperimentRun(
                PauseCommand.experiment(RUN, PauseCommand.Action.RESUME, "manual",
                        "自测：立即恢复", "SELF-ERR-NORMAL"), "normal");
        check("正常恢复 requeued=true", r.requeued(), r);
        check("正常恢复 control_state 已回 NONE",
                "NONE".equals(controlState()), controlState());
        check("正常恢复 run 已入队",
                scheduler.snapshot().queue().stream()
                        .anyMatch(q -> q.experimentRunId() == RUN),
                scheduler.snapshot().queue());
    }

    private static void blockedResumeStaysPaused() throws Exception {
        resetPaused();
        RuntimeHold hold = new RuntimeHold(db, TENANT);
        hold.raise(RuntimeHold.Scope.SITE, null, RuntimeHold.Kind.FAULT,
                "自测：全场故障", null);
        try {
            PauseCoordinator coord = new PauseCoordinator(db, TENANT, null, null);
            PauseCoordinator.ExperimentResume r = coord.resumeExperimentRun(
                    PauseCommand.experiment(RUN, PauseCommand.Action.RESUME, "manual",
                            "自测：现场仍扣留", "SELF-ERR-BLOCK"), "blocked");
            check("site Hold 下 requeued=false", !r.requeued(), r);
            check("site Hold 下 run 保持 paused",
                    "paused".equals(status()), status());
            check("site Hold 下 control_state 仍为 NONE",
                    "NONE".equals(controlState()), controlState());
        } finally {
            hold.clear(RuntimeHold.Scope.SITE, null, null, "自测：清 site hold");
        }
    }

    private static void rehydrateRecoversQueuedResumingRun() throws Exception {
        resetPaused();
        PauseCoordinator coord = new PauseCoordinator(db, TENANT, null, null);
        PauseCoordinator.ExperimentResume r = coord.resumeExperimentRun(
                PauseCommand.experiment(RUN, PauseCommand.Action.RESUME, "manual",
                        "自测：等待重启恢复", "SELF-ERR-REHYDRATE"), "rehydrate");
        check("无 scheduler 时先落 queued", r.requeued() && "queued".equals(status()), status());
        check("无 scheduler 时 control_state=RESUMING",
                "RESUMING".equals(controlState()), controlState());

        ExperimentScheduler restarted = new ExperimentScheduler(db, TENANT, null);
        int added = restarted.rehydrateQueued();
        check("重启后 rehydrate 收回 queued run", added >= 1, added);
        check("重启后 run 已回队列",
                restarted.snapshot().queue().stream()
                        .anyMatch(q -> q.experimentRunId() == RUN),
                restarted.snapshot().queue());
        check("rehydrate 后 control_state 回 NONE",
                "NONE".equals(controlState()), controlState());
    }

    private static void seed() throws SQLException {
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO experiment (id, tenant_id, experiment_no, name, status)
                    VALUES (?, ?, 'SELF-ERR-EXP', 'SELF-ERR-EXP', 'created')
                    """)) {
                ps.setLong(1, EXP);
                ps.setLong(2, TENANT);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO experiment_run (id, tenant_id, experiment_id, status, control_state)
                    VALUES (?, ?, ?, 'paused', 'NONE')
                    """)) {
                ps.setLong(1, RUN);
                ps.setLong(2, TENANT);
                ps.setLong(3, EXP);
                ps.executeUpdate();
            }
        }
    }

    private static void resetPaused() throws SQLException {
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE experiment_run
                       SET status = 'paused', control_state = 'NONE',
                           stop_requested = false, error = NULL,
                           pause_kind = 'manual', pause_reason = '自测',
                           paused_at = now(), finished_at = NULL
                     WHERE tenant_id = ? AND id = ?
                    """)) {
                ps.setLong(1, TENANT);
                ps.setLong(2, RUN);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    DELETE FROM runtime_hold
                     WHERE tenant_id = ? AND (
                           (scope = 'experiment' AND scope_id = ?)
                        OR scope = 'site'
                     )
                    """)) {
                ps.setLong(1, TENANT);
                ps.setLong(2, EXP);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    DELETE FROM pause_event
                     WHERE tenant_id = ? AND request_id LIKE 'SELF-ERR-%'
                    """)) {
                ps.setLong(1, TENANT);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    DELETE FROM pause_request
                     WHERE tenant_id = ? AND request_id LIKE 'SELF-ERR-%'
                    """)) {
                ps.setLong(1, TENANT);
                ps.executeUpdate();
            }
        }
    }

    private static String status() throws SQLException {
        return one("SELECT status::text FROM experiment_run WHERE tenant_id = 1 AND id = " + RUN);
    }

    private static String controlState() throws SQLException {
        return one("SELECT control_state FROM experiment_run WHERE tenant_id = 1 AND id = " + RUN);
    }

    private static String one(String sql) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void cleanup() throws SQLException {
        try (Connection c = db.open()) {
            exec(c, "DELETE FROM pause_event WHERE tenant_id = ? AND request_id LIKE 'SELF-ERR-%'",
                    TENANT);
            exec(c, "DELETE FROM pause_request WHERE tenant_id = ? AND request_id LIKE 'SELF-ERR-%'",
                    TENANT);
            exec(c, "DELETE FROM runtime_hold WHERE tenant_id = ? AND ((scope='experiment' AND scope_id = ?) OR scope='site')",
                    TENANT, EXP);
            exec(c, "DELETE FROM experiment_run WHERE tenant_id = ? AND id = ?", TENANT, RUN);
            exec(c, "DELETE FROM experiment WHERE tenant_id = ? AND id = ?", TENANT, EXP);
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

    private static void check(String name, boolean ok, Object actual) {
        if (ok) {
            pass++;
            System.out.println("  ✓ " + name + " -> " + actual);
        } else {
            fail++;
            System.out.println("  ✗ " + name + "（实际：" + actual + "）");
        }
    }

    private ExperimentResumeRecoverySelfTest() {
    }
}
