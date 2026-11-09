package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * FIX-P0-04：Workflow 级 queued resume 的启动 rehydrate 自测（需要真实库）。
 */
public final class WorkflowResumeRecoverySelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;
    private static final long TENANT = 1L;
    private static final long RUN_A = -993401L;
    private static final long RUN_B = -993402L;
    private static final long RUN_PAUSED = -993403L;
    private static final long RUN_NO_WF = -993404L;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();
        try {
            seed();
            rehydrateStartsQueuedWorkflowRunsOnly();
            rehydrateContinuesAfterSingleRunFailure();
            rehydrateRecoversQueuedRunAfterRestartWindow();
        } finally {
            cleanup();
        }
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void rehydrateStartsQueuedWorkflowRunsOnly() throws Exception {
        resetQueued();
        List<Long> started = new ArrayList<>();
        WorkflowResumeRecovery.Result r = WorkflowResumeRecovery.rehydrateQueued(db, TENANT, runId -> {
            started.add(runId);
            markRunning(runId);
        });
        check("只扫描到 2 条 queued workflow run", r.scanned() == 2, r);
        check("成功启动 2 条 queued workflow run", r.started() == 2 && r.failed() == 0, r);
        check("只启动 queued workflow run", started.contains(RUN_A) && started.contains(RUN_B),
                started);
        check("paused / 无 workflow_id 不参与恢复",
                !started.contains(RUN_PAUSED) && !started.contains(RUN_NO_WF), started);
    }

    private static void rehydrateContinuesAfterSingleRunFailure() throws Exception {
        resetQueued();
        List<Long> started = new ArrayList<>();
        WorkflowResumeRecovery.Result r = WorkflowResumeRecovery.rehydrateQueued(db, TENANT, runId -> {
            if (runId == RUN_A) {
                throw new IllegalStateException("boom");
            }
            started.add(runId);
            markRunning(runId);
        });
        check("单条启动失败不影响其它 queued run", r.started() == 1 && r.failed() == 1, r);
        check("失败 run 仍保留 queued 以待下次恢复",
                "queued".equals(statusOf(RUN_A)), statusOf(RUN_A));
        check("成功 run 已转 running",
                "running".equals(statusOf(RUN_B)), statusOf(RUN_B));
    }

    private static void rehydrateRecoversQueuedRunAfterRestartWindow() throws Exception {
        resetQueued();
        markPaused(RUN_B);
        WorkflowResumeRecovery.Result r = WorkflowResumeRecovery.rehydrateQueued(db, TENANT, runId -> {
            markRunning(runId);
        });
        check("重启恢复能拾起遗留 queued run", r.started() == 1 && r.failed() == 0, r);
        check("遗留 queued run 被转 running",
                "running".equals(statusOf(RUN_A)), statusOf(RUN_A));
    }

    private static void seed() throws SQLException {
        try (Connection c = db.open()) {
            insertRun(c, RUN_A, "SELF-WRR-A", 239L, "queued");
            insertRun(c, RUN_B, "SELF-WRR-B", 239L, "queued");
            insertRun(c, RUN_PAUSED, "SELF-WRR-P", 239L, "paused");
            insertRun(c, RUN_NO_WF, "SELF-WRR-N", null, "queued");
        }
    }

    private static void insertRun(Connection c, long runId, String runNo, Long workflowId, String status)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO run (id, tenant_id, run_no, run_type, workflow_id, workflow_ver,
                                 mode, status, actual_start, progress, resumed_at)
                VALUES (?, ?, ?, 'workflow', ?, 1, 'normal', ?::run_status, now(), 0, now())
                """)) {
            ps.setLong(1, runId);
            ps.setLong(2, TENANT);
            ps.setString(3, runNo);
            if (workflowId == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, workflowId);
            }
            ps.setString(5, status);
            ps.executeUpdate();
        }
    }

    private static void resetQueued() throws SQLException {
        try (Connection c = db.open()) {
            exec(c, "UPDATE run SET status='queued', resumed_at=now() WHERE tenant_id=? AND id IN (?, ?)",
                    TENANT, RUN_A, RUN_B);
            exec(c, "UPDATE run SET status='paused' WHERE tenant_id=? AND id=?",
                    TENANT, RUN_PAUSED);
            exec(c, "UPDATE run SET status='queued' WHERE tenant_id=? AND id=?",
                    TENANT, RUN_NO_WF);
        }
    }

    private static void markRunning(long runId) throws SQLException {
        try (Connection c = db.open()) {
            exec(c, "UPDATE run SET status='running' WHERE tenant_id=? AND id=?", TENANT, runId);
        }
    }

    private static void markPaused(long runId) throws SQLException {
        try (Connection c = db.open()) {
            exec(c, "UPDATE run SET status='paused' WHERE tenant_id=? AND id=?", TENANT, runId);
        }
    }

    private static String statusOf(long runId) throws SQLException {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status::text FROM run WHERE tenant_id=? AND id=?")) {
            ps.setLong(1, TENANT);
            ps.setLong(2, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void cleanup() throws SQLException {
        try (Connection c = db.open()) {
            exec(c, "DELETE FROM run WHERE tenant_id=? AND id IN (?, ?, ?, ?)",
                    TENANT, RUN_A, RUN_B, RUN_PAUSED, RUN_NO_WF);
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

    private WorkflowResumeRecoverySelfTest() {
    }
}
