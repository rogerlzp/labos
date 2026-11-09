package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * P-09 自测：run.exec_phase 单向推进、幂等、且"没动过设备"只认 PREPARED。
 *
 * <p>这条标记存在的唯一理由，是崩溃后能区分「可以安全重来」和「只能隔离核对」。
 * 所以断言的重点不是"能写"，而是**不能倒退**：一旦派发过，任何重复调用都不许
 * 把它降回 PREPARED —— 那会让恢复逻辑以为设备没动过。
 */
public final class RunExecPhaseDbSelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;
    private static final long TENANT = 1L;
    private static final String PREFIX = "SELF-EP-" + System.currentTimeMillis() + "-";

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        long r1 = insertRun(PREFIX + "a");
        long r2 = insertRun(PREFIX + "b");
        try {
            check("建单默认 PREPARED", RunExecPhase.PREPARED.equals(RunExecPhase.of(db, TENANT, r1)),
                    RunExecPhase.of(db, TENANT, r1));
            check("PREPARED 视为「没动过设备」", !RunExecPhase.maybeDispatched(db, TENANT, r1),
                    RunExecPhase.maybeDispatched(db, TENANT, r1));

            RunExecPhase.markDispatched(db, TENANT, r1);
            check("派发前升级到 DISPATCH_STARTED",
                    RunExecPhase.DISPATCH_STARTED.equals(RunExecPhase.of(db, TENANT, r1)),
                    RunExecPhase.of(db, TENANT, r1));
            check("已派发 ⇒ maybeDispatched=true",
                    RunExecPhase.maybeDispatched(db, TENANT, r1),
                    RunExecPhase.maybeDispatched(db, TENANT, r1));

            RunExecPhase.markDispatched(db, TENANT, r1);
            check("重复 markDispatched 幂等（仍 DISPATCH_STARTED）",
                    RunExecPhase.DISPATCH_STARTED.equals(RunExecPhase.of(db, TENANT, r1)),
                    RunExecPhase.of(db, TENANT, r1));

            RunExecPhase.markSettled(db, TENANT, r1);
            check("收尾落 SETTLED",
                    RunExecPhase.SETTLED.equals(RunExecPhase.of(db, TENANT, r1)),
                    RunExecPhase.of(db, TENANT, r1));

            RunExecPhase.markDispatched(db, TENANT, r1);
            check("SETTLED 之后不许被 markDispatched 降级",
                    RunExecPhase.SETTLED.equals(RunExecPhase.of(db, TENANT, r1)),
                    RunExecPhase.of(db, TENANT, r1));
            RunExecPhase.markSettled(db, TENANT, r1);
            check("重复 markSettled 幂等",
                    RunExecPhase.SETTLED.equals(RunExecPhase.of(db, TENANT, r1)),
                    RunExecPhase.of(db, TENANT, r1));

            check("读不到的行返回 null（不假装 PREPARED）",
                    RunExecPhase.of(db, TENANT, -1L) == null, null);
            check("读不到就当「可能已派发」（fail-closed）",
                    RunExecPhase.maybeDispatched(db, TENANT, -1L), true);
        } finally {
            deleteRun(r1);
            deleteRun(r2);
            System.out.printf("%n通过 %d, 失败 %d%n", pass, fail);
        }
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static long insertRun(String runNo) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO run (tenant_id, run_no, run_type, status, mode, priority, created_at)
                     VALUES (?, ?, 'workflow', 'running', 'normal', 5, now())
                     RETURNING id
                     """)) {
            ps.setLong(1, TENANT);
            ps.setString(2, runNo);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void deleteRun(long runId) {
        if (runId <= 0) {
            return;
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM run WHERE id = ?")) {
            ps.setLong(1, runId);
            ps.executeUpdate();
        } catch (Exception e) {
            System.out.println("  （清理 run#" + runId + " 失败：" + e.getMessage() + "）");
        }
    }

    private static void check(String name, boolean ok, Object actual) {
        if (ok) {
            pass++;
            System.out.println("  ✓ " + name + " " + actual);
        } else {
            fail++;
            System.out.println("  ✗ " + name + " " + actual);
        }
    }

    private RunExecPhaseDbSelfTest() {
    }
}
