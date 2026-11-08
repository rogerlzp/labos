package com.labos.kernel.driver;

import com.labos.kernel.persistence.DbConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * 设备命令账本单测（P-07 第一切片，需要真实库）。
 *
 * <p>验证四条不能商量的规则：
 * <ol>
 *   <li>同 idempotency_key 只落一行，第二次 begin 返回 created=false（禁止重复下发）；</li>
 *   <li>finish 后状态 ACKED / FAILED，且重放 begin 看到终态；</li>
 *   <li>陈旧 DISPATCH_STARTED 启动对账标 UNKNOWN；</li>
 *   <li>非陈旧 DISPATCH_STARTED 不被误标（不能把正在跑的命令当成崩溃残留）。</li>
 * </ol>
 */
public final class CommandLedgerDbSelfTest {

    private static int pass;
    private static int fail;
    private static DbConfig db;
    private static final long TENANT = 1L;
    private static final String PREFIX = "SELF-CL-" + System.currentTimeMillis() + "-";

    public static void main(String[] args) throws Exception {
        db = DbConfig.fromEnv();
        cleanup();
        try {
            testBeginDuplicateAndAck();
            testFailureState();
            testReconcileStale();
        } finally {
            cleanup();
        }
        System.out.println("通过 " + pass + ", 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void testBeginDuplicateAndAck() throws Exception {
        String key = PREFIX + "ack";
        CommandLedger.Entry first = begin(key, "cmd-ack");
        check("派发前账本创建成功", first.created() && "DISPATCH_STARTED".equals(first.state()),
                first.state());
        CommandLedger.Entry second = begin(key, "cmd-ack");
        check("同一 commandId 不重复落账（只 1 行）",
                !second.created() && second.id() == first.id(), second);
        check("库里确实只有 1 行", rowCount(key) == 1, rowCount(key));

        CommandLedger.finish(db, first.id(), true, null, null, "cmd-ack");
        check("回包成功后状态 ACKED", "ACKED".equals(CommandLedger.stateOf(db, TENANT, key)),
                CommandLedger.stateOf(db, TENANT, key));
        CommandLedger.Entry replay = begin(key, "cmd-ack");
        check("重放 begin 看到终态 ACKED 并拒绝再发",
                !replay.created() && "ACKED".equals(replay.state()), replay.state());
    }

    private static void testFailureState() throws Exception {
        String key = PREFIX + "fail";
        CommandLedger.Entry e = begin(key, "cmd-fail");
        CommandLedger.finish(db, e.id(), false, "EC-99-0005", "设备命令被暂停中止", "cmd-fail");
        check("失败状态落 FAILED", "FAILED".equals(CommandLedger.stateOf(db, TENANT, key)),
                CommandLedger.stateOf(db, TENANT, key));
        check("失败错误码可追溯", "EC-99-0005".equals(errorCodeOf(key)), errorCodeOf(key));
    }

    private static void testReconcileStale() throws Exception {
        String staleKey = PREFIX + "stale";
        CommandLedger.Entry stale = begin(staleKey, "cmd-stale");
        exec("UPDATE command_ledger SET created_at = now() - interval '1 hour', "
                + "updated_at = now() - interval '1 hour' WHERE id = " + stale.id());
        int marked = CommandLedger.reconcileOnStartup(db, TENANT, CommandLedger.STALE_MINUTES);
        check("启动对账把陈旧派发标 UNKNOWN（本次 " + marked + " 条）", marked >= 1, marked);
        check("陈旧行状态 UNKNOWN", "UNKNOWN".equals(CommandLedger.stateOf(db, TENANT, staleKey)),
                CommandLedger.stateOf(db, TENANT, staleKey));

        String freshKey = PREFIX + "fresh";
        begin(freshKey, "cmd-fresh");
        int markedAgain = CommandLedger.reconcileOnStartup(db, TENANT, CommandLedger.STALE_MINUTES);
        check("非陈旧 DISPATCH_STARTED 不被误标（本次 " + markedAgain + " 条）",
                "DISPATCH_STARTED".equals(CommandLedger.stateOf(db, TENANT, freshKey)),
                CommandLedger.stateOf(db, TENANT, freshKey));
    }

    private static CommandLedger.Entry begin(String key, String commandId) throws Exception {
        return CommandLedger.begin(db, TENANT, key, 999999L, 42L, 1, 1,
                200214L, "Home", commandId, "trace-selftest");
    }

    private static int rowCount(String key) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM command_ledger "
                             + "WHERE tenant_id = ? AND idempotency_key = ?")) {
            ps.setLong(1, TENANT);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static String errorCodeOf(String key) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT error_code FROM command_ledger "
                             + "WHERE tenant_id = ? AND idempotency_key = ?")) {
            ps.setLong(1, TENANT);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void exec(String sql) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private static void cleanup() {
        try {
            exec("DELETE FROM command_ledger "
                    + "WHERE tenant_id = 1 AND idempotency_key LIKE '" + PREFIX + "%'");
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
