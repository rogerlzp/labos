package com.labos.kernel.workflow;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

/** P-08 启动恢复隔离（QUARANTINED）自测（需要真实库）。 */
public final class QuarantineDbSelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;
    private static final long TENANT = 1L;
    private static final long EXP = -995001L;
    private static final long ERUN = -995002L;
    private static final long RUN = -995003L;
    private static final long STEP = -995004L;
    /** 自建流程（负数 id 不撞 identity 序列）——
     *  原来这里硬编码 workflow_id=239，那是旧基线里某条真实流程。
     *  旧基线拆掉后 239 没了 ⇒ 本自测直接 FK 报错、整条用例跑不起来。
     *  ⇒ 改成自建：自测**不依赖任何历史数据**（这本来就是它该有的样子）。 */
    private static final long WF = -995005L;

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();
        seed();
        // P-08：普通 settle 必须跳过物理未知 run（不落 failed、不释放设备锁）
        List<StartupRecovery.Orphan> settled =
                StartupRecovery.settle(db, TENANT, List.of(RUN));
        check("物理未知 run 不被普通启动 settle 收掉",
                settled.stream().noneMatch(o -> o.runId() == RUN), settled);
        check("settle 后 run 仍是 running", "running".equals(
                str("SELECT status::text FROM run WHERE id=" + RUN)),
                str("SELECT status::text FROM run WHERE id=" + RUN));

        List<StartupRecovery.Quarantined> list =
                StartupRecovery.quarantineUncertainRuns(db, TENANT, List.of(RUN));
        check("物理未知 run 被隔离", list.size() == 1 && list.get(0).runId() == RUN, list);
        check("run.status=quarantined", "quarantined".equals(str("SELECT status::text FROM run WHERE id=" + RUN)),
                str("SELECT status::text FROM run WHERE id=" + RUN));
        check("input.quarantine=true",
                "true".equals(str("SELECT input->>'quarantine' FROM run WHERE id=" + RUN)),
                str("SELECT input->>'quarantine' FROM run WHERE id=" + RUN));
        check("隔离抬实验级 Hold", holdCount() == 1, holdCount());
        new RuntimeHold(db, TENANT).raise(RuntimeHold.Scope.EXPERIMENT, EXP,
                RuntimeHold.Kind.MANUAL, "自测：人工暂停", null);
        new RuntimeHold(db, TENANT).raise(RuntimeHold.Scope.EXPERIMENT, EXP,
                RuntimeHold.Kind.FAULT, "自测：设备故障", null);
        check("隔离外的 manual/fault Hold 可并存", holdCount() == 3, holdCount());
        check("隔离 Hold 为 HUMAN", holdCount(RuntimeHold.Kind.HUMAN) == 1,
                holdCount(RuntimeHold.Kind.HUMAN));
        check("人工暂停 Hold 仍在", holdCount(RuntimeHold.Kind.MANUAL) == 1,
                holdCount(RuntimeHold.Kind.MANUAL));
        check("故障 Hold 仍在", holdCount(RuntimeHold.Kind.FAULT) == 1,
                holdCount(RuntimeHold.Kind.FAULT));
        check("隔离原因可追溯",
                (str("SELECT error_code FROM run WHERE id=" + RUN) != null),
                str("SELECT error_code FROM run WHERE id=" + RUN));

        boolean ok = StartupRecovery.resolveQuarantine(db, TENANT, RUN, 1L, "人工确认设备未动作");
        check("人工确认后解除隔离", ok, ok);
        check("解除后 run=failed", "failed".equals(str("SELECT status::text FROM run WHERE id=" + RUN)),
                str("SELECT status::text FROM run WHERE id=" + RUN));
        check("解除后错误码=EC-QUARANTINE-CONFIRMED",
                "EC-QUARANTINE-CONFIRMED".equals(str("SELECT error_code FROM run WHERE id=" + RUN)),
                str("SELECT error_code FROM run WHERE id=" + RUN));
        check("解除后只释放隔离 Hold（HUMAN）",
                holdCount(RuntimeHold.Kind.HUMAN) == 0, holdCount(RuntimeHold.Kind.HUMAN));
        check("解除后 manual Hold 不误清",
                holdCount(RuntimeHold.Kind.MANUAL) == 1, holdCount(RuntimeHold.Kind.MANUAL));
        check("解除后 fault Hold 不误清",
                holdCount(RuntimeHold.Kind.FAULT) == 1, holdCount(RuntimeHold.Kind.FAULT));
        check("解除后仅剩其它 Hold", holdCount() == 2, holdCount());
        check("二次解除返回 false",
                !StartupRecovery.resolveQuarantine(db, TENANT, RUN, 1L, "again"), true);
        cleanup();
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void seed() throws Exception {
        // 自建流程（见 WF 常量的说明）：自测不依赖旧基线的任何一行数据
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO workflow (id, tenant_id, code, name, category, version, status)
                     VALUES (?, 1, 'SELF-Q-WF', 'SELF-Q-WF', 'choreography', 1, 1)
                     """)) {
            ps.setLong(1, WF);
            ps.executeUpdate();
        } catch (Exception ignored) {
            // 已存在
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO experiment (id, tenant_id, experiment_no, name, status)
                     VALUES (?, 1, 'SELF-Q-EXP', 'SELF-Q-EXP', 'created')
                     """)) {
            ps.setLong(1, EXP);
            ps.executeUpdate();
        } catch (Exception ignored) {
            // 已存在
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO experiment_run (id, tenant_id, experiment_id, status)
                     VALUES (?, 1, ?, 'running')
                     """)) {
            ps.setLong(1, ERUN);
            ps.setLong(2, EXP);
            ps.executeUpdate();
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO run (id, tenant_id, run_no, run_type, workflow_id,
                                      workflow_ver, mode, status, actual_start, progress)
                     VALUES (?, 1, 'SELF-Q-RUN', 'workflow', ?, 1, 'normal',
                             'running', now(), 0)
                     """)) {
            ps.setLong(1, RUN);
            ps.setLong(2, WF);
            ps.executeUpdate();
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO experiment_run_step
                       (id, tenant_id, experiment_run_id, seq, workflow_id, run_id, status)
                     VALUES (?, 1, ?, 1, ?, ?, 'running')
                     """)) {
            ps.setLong(1, STEP);
            ps.setLong(2, ERUN);
            ps.setLong(3, WF);
            ps.setLong(4, RUN);
            ps.executeUpdate();
        }
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO command_ledger
                       (tenant_id, idempotency_key, run_id, node_id, method, state,
                        error_code, error_message)
                     VALUES (1, 'SELF-Q-LEDGER', ?, 1, 'Home', 'UNKNOWN',
                             'EC-99-0006', '自测')
                     """)) {
            ps.setLong(1, RUN);
            ps.executeUpdate();
        }
    }

    private static String str(String sql) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static int holdCount() throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM runtime_hold WHERE tenant_id=1 "
                             + "AND scope='experiment' AND scope_id=? AND released_at IS NULL")) {
            ps.setLong(1, EXP);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static int holdCount(RuntimeHold.Kind kind) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM runtime_hold WHERE tenant_id=1 "
                             + "AND scope='experiment' AND scope_id=? "
                             + "AND kind=? AND released_at IS NULL")) {
            ps.setLong(1, EXP);
            ps.setString(2, kind.code());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static void cleanup() throws Exception {
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM runtime_hold WHERE tenant_id=1 AND scope='experiment' AND scope_id=?")) {
                ps.setLong(1, EXP);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM command_ledger WHERE tenant_id=1 AND idempotency_key='SELF-Q-LEDGER'")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM experiment_run_step WHERE tenant_id=1 AND id=?")) {
                ps.setLong(1, STEP);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM experiment_run WHERE tenant_id=1 AND id=?")) {
                ps.setLong(1, ERUN);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM experiment WHERE tenant_id=1 AND id=?")) {
                ps.setLong(1, EXP);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM run WHERE tenant_id=1 AND id=?")) {
                ps.setLong(1, RUN);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM workflow WHERE tenant_id=1 AND id=?")) {
                ps.setLong(1, WF);
                ps.executeUpdate();
            }
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
