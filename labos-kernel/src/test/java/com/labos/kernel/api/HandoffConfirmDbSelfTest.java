package com.labos.kernel.api;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/** N-10 Handoff 运行时人工确认（配置 → confirmed + L-01 介入记录）自测。 */
public final class HandoffConfirmDbSelfTest {

    private static int pass;
    private static int fail;
    private static final long TENANT = 1L;
    private static final long EXP = -996001L;
    private static final long A = 996101L;
    private static final long B = 996102L;

    public static void main(String[] args) throws Exception {
        DbConfig db = DbConfig.fromEnv();
        cleanup(db);
        seed(db);
        QueryService q = new QueryService(db, TENANT);
        int n = q.confirmHandoff(EXP, A, B, "tester", "现场确认交接完成");
        check("配置态 Handoff 可人工确认", n == 1, n);
        check("确认后 status=confirmed", "confirmed".equals(status(db)),
                status(db));
        check("确认落 L-01 介入记录", recordCount(db) == 1, recordCount(db));
        int again = q.confirmHandoff(EXP, A, B, "tester", "again");
        check("重复确认返回 0", again == 0, again);
        check("Handoff 与介入记录强关联（intervention_record_id）",
                interventionId(db) > 0, interventionId(db));
        cleanup(db);
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void seed(DbConfig db) throws Exception {
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO experiment (id, tenant_id, experiment_no, name, status) "
                            + "VALUES (?, 1, 'SELF-HO-EXP', 'SELF-HO-EXP', 'created')")) {
                ps.setLong(1, EXP);
                ps.executeUpdate();
            }
            for (long wc : new long[]{A, B}) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO experiment_workcell (tenant_id, experiment_id, workcell_id) "
                                + "VALUES (1, ?, ?)")) {
                    ps.setLong(1, EXP);
                    ps.setLong(2, wc);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO experiment_handoff (tenant_id, experiment_id, "
                            + "from_workcell_id, to_workcell_id) VALUES (1, ?, ?, ?)")) {
                ps.setLong(1, EXP);
                ps.setLong(2, A);
                ps.setLong(3, B);
                ps.executeUpdate();
            }
        }
    }

    private static String status(DbConfig db) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status FROM experiment_handoff WHERE tenant_id=1 AND experiment_id=?")) {
            ps.setLong(1, EXP);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static long interventionId(DbConfig db) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT intervention_record_id FROM experiment_handoff "
                             + "WHERE tenant_id=1 AND experiment_id=?")) {
            ps.setLong(1, EXP);
            try (ResultSet rs = ps.executeQuery()) {
                long v = rs.next() ? rs.getLong(1) : 0L;
                return rs.wasNull() ? 0L : v;
            }
        }
    }

    private static int recordCount(DbConfig db) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM intervention_record WHERE tenant_id=1 "
                             + "AND decided_action='HANDOFF_CONFIRM' "
                             + "AND from_location=? AND to_location=?")) {
            ps.setString(1, String.valueOf(A));
            ps.setString(2, String.valueOf(B));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static void cleanup(DbConfig db) {
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM intervention_record WHERE tenant_id=1 "
                            + "AND decided_action='HANDOFF_CONFIRM' "
                            + "AND from_location=? AND to_location=?")) {
                ps.setString(1, String.valueOf(A));
                ps.setString(2, String.valueOf(B));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM experiment_handoff WHERE tenant_id=1 AND experiment_id=?")) {
                ps.setLong(1, EXP);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM experiment_workcell WHERE tenant_id=1 AND experiment_id=?")) {
                ps.setLong(1, EXP);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM experiment WHERE tenant_id=1 AND id=?")) {
                ps.setLong(1, EXP);
                ps.executeUpdate();
            }
        } catch (Exception ignored) {
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
